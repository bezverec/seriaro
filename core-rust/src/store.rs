use std::collections::HashSet;
use std::path::PathBuf;
use std::sync::{Mutex, MutexGuard};
use std::time::Duration;

use rusqlite::{params, Connection, OptionalExtension, Row, Transaction};
use serde::{Deserialize, Serialize};

use crate::tvmaze::{catalog_url, clean_poster_url, is_tvmaze, CREDIT, ID_NAMESPACE};
use crate::{
    validated_settings, Catalog, CoreError, Dashboard, Episode, EpisodeKey, EpisodeState, Premiere,
    Result, Series, SeriesProgress, Settings,
};

pub(crate) const MAX_BACKUP_BYTES: usize = 32 * 1024 * 1024;
const SCHEMA_VERSION: u32 = 1;
const DB_VERSION: u32 = 2;

const SCHEMA_V1: &str = "
CREATE TABLE settings (id INTEGER PRIMARY KEY CHECK (id = 1), value TEXT NOT NULL);
CREATE TABLE following (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    overview TEXT NOT NULL,
    first_air_date TEXT,
    catalog_language TEXT,
    last_refresh TEXT
);
CREATE TABLE episodes (
    series_id INTEGER NOT NULL REFERENCES following(id) ON DELETE CASCADE,
    season INTEGER NOT NULL,
    number INTEGER NOT NULL CHECK (number > 0),
    name TEXT NOT NULL,
    overview TEXT NOT NULL,
    air_date TEXT,
    PRIMARY KEY (series_id, season, number)
);
CREATE INDEX episodes_by_date ON episodes(air_date, series_id);
CREATE TABLE watched (
    series_id INTEGER NOT NULL REFERENCES following(id) ON DELETE CASCADE,
    season INTEGER NOT NULL,
    number INTEGER NOT NULL CHECK (number > 0),
    PRIMARY KEY (series_id, season, number)
);
CREATE TABLE notified (
    series_id INTEGER NOT NULL REFERENCES following(id) ON DELETE CASCADE,
    season INTEGER NOT NULL,
    number INTEGER NOT NULL,
    air_date TEXT NOT NULL,
    PRIMARY KEY (series_id, season, number, air_date)
);";

pub(crate) struct Store(Mutex<Connection>);

#[derive(Serialize, Deserialize)]
struct Backup {
    schema_version: u32,
    following: Vec<Catalog>,
    watched: Vec<EpisodeKey>,
    settings: Settings,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    catalog_attribution: Option<String>,
}

#[derive(Deserialize)]
struct BackupHeader {
    schema_version: u32,
}

impl Store {
    pub(crate) fn open(path: PathBuf) -> Result<Self> {
        let mut conn = Connection::open(path)?;
        conn.busy_timeout(Duration::from_secs(5))?;
        conn.pragma_update(None, "foreign_keys", true)?;
        conn.pragma_update(None, "journal_mode", "WAL")?;
        migrate(&mut conn)?;
        Ok(Self(Mutex::new(conn)))
    }

    fn lock(&self) -> Result<MutexGuard<'_, Connection>> {
        self.0.lock().map_err(|_| CoreError::Database)
    }

    pub(crate) fn settings(&self) -> Result<Settings> {
        read_settings(&*self.lock()?)
    }

    pub(crate) fn set_settings(&self, settings: &Settings) -> Result<()> {
        write_settings(&*self.lock()?, settings)
    }

    pub(crate) fn dashboard(&self, start: &str, end: &str) -> Result<Dashboard> {
        let conn = self.lock()?;
        let mut stmt = conn.prepare(
            "SELECT f.id, f.name, f.overview, f.first_air_date, f.poster_url, f.archived,
                    (SELECT COUNT(*) FROM watched w JOIN episodes USING (series_id, season, number)
                     WHERE w.series_id = f.id),
                    (SELECT COUNT(*) FROM episodes e WHERE e.series_id = f.id)
             FROM following f ORDER BY f.name COLLATE NOCASE, f.id",
        )?;
        let following = stmt
            .query_map([], |row| {
                Ok(SeriesProgress {
                    series: read_series(row)?,
                    archived: row.get(5)?,
                    watched_count: row.get(6)?,
                    episode_count: row.get(7)?,
                })
            })?
            .collect::<rusqlite::Result<_>>()?;
        Ok(Dashboard {
            settings: read_settings(&conn)?,
            following,
            upcoming: read_premieres(&conn, start, end, false)?,
        })
    }

    pub(crate) fn save_catalog(&self, catalog: &Catalog, today: &str, follow: bool) -> Result<()> {
        catalog.validate()?;
        let mut conn = self.lock()?;
        let tx = conn.transaction()?;
        let exists = tx.query_row(
            "SELECT EXISTS(SELECT 1 FROM following WHERE id = ?)",
            [catalog.series.id],
            |row| row.get::<_, bool>(0),
        )?;
        if follow || exists {
            replace_catalog(&tx, catalog, Some(today))?;
        }
        tx.commit()?;
        Ok(())
    }

    pub(crate) fn due(&self, today: &str) -> Result<Vec<u32>> {
        let conn = self.lock()?;
        let mut stmt = conn.prepare(
            "SELECT id FROM following
             WHERE id > ? AND (last_refresh IS NULL OR last_refresh <> ?)
             ORDER BY id",
        )?;
        let result = stmt
            .query_map(params![ID_NAMESPACE, today], |row| row.get(0))?
            .collect::<rusqlite::Result<_>>()?;
        Ok(result)
    }

    pub(crate) fn relink(&self, legacy_id: u32, catalog: &Catalog, today: &str) -> Result<()> {
        catalog.validate()?;
        if !(1..ID_NAMESPACE).contains(&legacy_id) || !is_tvmaze(catalog.series.id) {
            return Err(CoreError::InvalidItem);
        }
        let mut conn = self.lock()?;
        let tx = conn.transaction()?;
        let exists = tx.query_row(
            "SELECT EXISTS(SELECT 1 FROM following WHERE id = ?)",
            [legacy_id],
            |row| row.get::<_, bool>(0),
        )?;
        if !exists {
            return Err(CoreError::InvalidItem);
        }
        replace_catalog(&tx, catalog, Some(today))?;
        let ids = params![catalog.series.id, legacy_id];
        tx.execute(
            "INSERT OR IGNORE INTO watched(series_id, season, number)
             SELECT ?, season, number FROM watched WHERE series_id = ?",
            ids,
        )?;
        tx.execute(
            "INSERT OR IGNORE INTO notified(series_id, season, number, air_date)
             SELECT ?, season, number, air_date FROM notified WHERE series_id = ?",
            ids,
        )?;
        tx.execute(
            "UPDATE following SET archived = 1
             WHERE id = ? AND EXISTS(SELECT 1 FROM following WHERE id = ? AND archived = 1)",
            ids,
        )?;
        tx.execute("DELETE FROM following WHERE id = ?", [legacy_id])?;
        tx.commit()?;
        Ok(())
    }

    pub(crate) fn unfollow(&self, id: u32) -> Result<()> {
        self.lock()?
            .execute("DELETE FROM following WHERE id = ?", [id])?;
        Ok(())
    }

    pub(crate) fn episodes(&self, id: u32) -> Result<Vec<EpisodeState>> {
        let conn = self.lock()?;
        read_episodes(&conn, id)
    }

    pub(crate) fn set_watched(&self, key: &EpisodeKey, watched: bool) -> Result<()> {
        let conn = self.lock()?;
        let identity = params![key.series_id, key.season, key.number];
        if watched {
            let exists: bool = conn.query_row(
                "SELECT EXISTS(
                   SELECT 1 FROM episodes WHERE series_id = ?1 AND season = ?2 AND number = ?3
                   UNION ALL
                   SELECT 1 FROM watched WHERE series_id = ?1 AND season = ?2 AND number = ?3
                 )",
                identity,
                |row| row.get(0),
            )?;
            if !exists {
                return Err(CoreError::InvalidItem);
            }
            conn.execute(
                "INSERT OR IGNORE INTO watched(series_id, season, number) VALUES (?, ?, ?)",
                identity,
            )?;
        } else {
            conn.execute(
                "DELETE FROM watched WHERE series_id = ? AND season = ? AND number = ?",
                identity,
            )?;
        }
        Ok(())
    }

    pub(crate) fn mark_episodes_watched(&self, id: u32, season: Option<u32>) -> Result<()> {
        self.lock()?.execute(
            "INSERT OR IGNORE INTO watched(series_id, season, number)
             SELECT series_id, season, number FROM episodes
             WHERE series_id = ?1 AND (?2 IS NULL OR season = ?2)",
            params![id, season],
        )?;
        Ok(())
    }

    pub(crate) fn mark_episodes_unwatched(&self, id: u32, season: Option<u32>) -> Result<()> {
        self.lock()?.execute(
            "DELETE FROM watched WHERE series_id = ?1 AND (?2 IS NULL OR season = ?2)",
            params![id, season],
        )?;
        Ok(())
    }

    pub(crate) fn watched_seasons(&self, id: u32) -> Result<Vec<u32>> {
        let conn = self.lock()?;
        let mut stmt = conn
            .prepare("SELECT DISTINCT season FROM watched WHERE series_id = ? ORDER BY season")?;
        let seasons = stmt
            .query_map([id], |row| row.get(0))?
            .collect::<rusqlite::Result<_>>()?;
        Ok(seasons)
    }

    pub(crate) fn set_archived(&self, id: u32, archived: bool) -> Result<()> {
        let updated = self.lock()?.execute(
            "UPDATE following SET archived = ?1 WHERE id = ?2
             AND (?1 = 0 OR archived = 1 OR (
                EXISTS(SELECT 1 FROM episodes WHERE series_id = ?2)
                AND NOT EXISTS(
                    SELECT 1 FROM episodes e LEFT JOIN watched w USING (series_id, season, number)
                    WHERE e.series_id = ?2 AND w.series_id IS NULL
                )
             ))",
            params![archived, id],
        )?;
        match (updated, archived) {
            (0, true) => Err(CoreError::IncompleteSeries),
            (0, false) => Err(CoreError::InvalidItem),
            _ => Ok(()),
        }
    }

    pub(crate) fn poster_url(&self, id: u32) -> Result<Option<Option<String>>> {
        Ok(self
            .lock()?
            .query_row(
                "SELECT poster_url FROM following WHERE id = ?",
                [id],
                |row| row.get(0),
            )
            .optional()?)
    }

    pub(crate) fn save_poster_if_unknown(&self, id: u32, url: Option<&str>) -> Result<()> {
        self.lock()?.execute(
            "UPDATE following SET poster_url = ? WHERE id = ? AND poster_url IS NULL",
            params![url.unwrap_or_default(), id],
        )?;
        Ok(())
    }

    pub(crate) fn premieres(
        &self,
        start: &str,
        end: &str,
        only_pending: bool,
    ) -> Result<Vec<Premiere>> {
        let conn = self.lock()?;
        read_premieres(&conn, start, end, only_pending)
    }

    pub(crate) fn mark_notified(&self, key: &EpisodeKey, date: &str) -> Result<()> {
        self.lock()?.execute(
            "INSERT OR IGNORE INTO notified(series_id, season, number, air_date)
             SELECT series_id, season, number, air_date FROM episodes
             WHERE series_id = ? AND season = ? AND number = ? AND air_date = ?",
            params![key.series_id, key.season, key.number, date],
        )?;
        Ok(())
    }

    pub(crate) fn export_backup(&self) -> Result<Vec<u8>> {
        let conn = self.lock()?;
        let mut stmt = conn.prepare(
            "SELECT id, name, overview, first_air_date, poster_url, archived FROM following ORDER BY id",
        )?;
        let series = stmt
            .query_map([], |row| Ok((read_series(row)?, row.get(5)?)))?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        let following = series
            .into_iter()
            .map(|(series, archived)| {
                let episodes = read_episodes(&conn, series.id)?
                    .into_iter()
                    .map(|state| state.episode)
                    .collect();
                Ok(Catalog {
                    series,
                    episodes,
                    archived,
                })
            })
            .collect::<Result<Vec<_>>>()?;
        let mut stmt = conn.prepare(
            "SELECT series_id, season, number FROM watched ORDER BY series_id, season, number",
        )?;
        let watched = stmt
            .query_map([], |row| {
                Ok(EpisodeKey {
                    series_id: row.get(0)?,
                    season: row.get(1)?,
                    number: row.get(2)?,
                })
            })?
            .collect::<rusqlite::Result<_>>()?;
        let catalog_attribution = following
            .iter()
            .any(|item: &Catalog| is_tvmaze(item.series.id))
            .then(|| CREDIT.to_owned());
        let backup = Backup {
            schema_version: SCHEMA_VERSION,
            following,
            watched,
            settings: read_settings(&conn)?,
            catalog_attribution,
        };
        let bytes = serde_json::to_vec_pretty(&backup).map_err(|_| CoreError::InvalidBackup)?;
        if bytes.len() > MAX_BACKUP_BYTES {
            return Err(CoreError::FileTooLarge);
        }
        Ok(bytes)
    }

    pub(crate) fn import_backup(&self, data: &[u8], merge: bool) -> Result<()> {
        let backup = decode_backup(data)?;
        let mut conn = self.lock()?;
        let tx = conn.transaction()?;
        if !merge {
            tx.execute("DELETE FROM following", [])?;
            write_settings(&tx, &backup.settings)?;
        }
        for catalog in backup.following {
            if merge {
                merge_catalog(&tx, &catalog)?;
            } else {
                replace_catalog(&tx, &catalog, None)?;
            }
        }
        for key in backup.watched {
            tx.execute(
                "INSERT OR IGNORE INTO watched(series_id, season, number) VALUES (?, ?, ?)",
                params![key.series_id, key.season, key.number],
            )?;
        }
        tx.commit()?;
        Ok(())
    }
}

fn migrate(conn: &mut Connection) -> Result<()> {
    let tx = conn.transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
    let version = tx.pragma_query_value(None, "user_version", |row| row.get::<_, u32>(0))?;
    match version {
        0 => tx.execute_batch(SCHEMA_V1)?,
        1 => {}
        DB_VERSION => return Ok(()),
        _ => return Err(CoreError::DatabaseVersion),
    }
    tx.execute_batch(
        "ALTER TABLE following ADD COLUMN poster_url TEXT;
         ALTER TABLE following ADD COLUMN archived INTEGER NOT NULL DEFAULT 0 CHECK (archived IN (0, 1));",
    )?;
    tx.pragma_update(None, "user_version", DB_VERSION)?;
    tx.commit()?;
    Ok(())
}

fn read_settings(conn: &Connection) -> Result<Settings> {
    let json: Option<String> = conn
        .query_row("SELECT value FROM settings WHERE id = 1", [], |row| {
            row.get(0)
        })
        .optional()?;
    match json {
        Some(json) => serde_json::from_str(&json).map_err(|_| CoreError::Database),
        None => Ok(Settings::default()),
    }
}

fn write_settings(conn: &Connection, settings: &Settings) -> Result<()> {
    let json = serde_json::to_string(settings).map_err(|_| CoreError::Database)?;
    conn.execute(
        "INSERT OR REPLACE INTO settings(id, value) VALUES (1, ?)",
        [json],
    )?;
    Ok(())
}

fn read_series(row: &Row<'_>) -> rusqlite::Result<Series> {
    let id = row.get(0)?;
    Ok(Series {
        id,
        name: row.get(1)?,
        overview: row.get(2)?,
        first_air_date: row.get(3)?,
        catalog_url: catalog_url(id),
        poster_url: row
            .get::<_, Option<String>>(4)?
            .as_deref()
            .and_then(clean_poster_url),
    })
}

fn read_episode(row: &Row<'_>) -> rusqlite::Result<Episode> {
    Ok(Episode {
        series_id: row.get(0)?,
        season: row.get(1)?,
        number: row.get(2)?,
        name: row.get(3)?,
        overview: row.get(4)?,
        air_date: row.get(5)?,
    })
}

const WATCHED_JOIN: &str = "LEFT JOIN watched w USING (series_id, season, number)";

fn read_episodes(conn: &Connection, id: u32) -> Result<Vec<EpisodeState>> {
    let mut stmt = conn.prepare(&format!(
        "SELECT e.series_id, e.season, e.number, e.name, e.overview, e.air_date, w.series_id IS NOT NULL
         FROM episodes e {WATCHED_JOIN} WHERE e.series_id = ? ORDER BY e.season, e.number"
    ))?;
    let results = stmt
        .query_map([id], |row| {
            Ok(EpisodeState {
                episode: read_episode(row)?,
                watched: row.get(6)?,
            })
        })?
        .collect::<rusqlite::Result<_>>()?;
    Ok(results)
}

fn read_premieres(
    conn: &Connection,
    start: &str,
    end: &str,
    only_pending: bool,
) -> Result<Vec<Premiere>> {
    let mut stmt = conn.prepare(&format!(
        "SELECT e.series_id, e.season, e.number, e.name, e.overview, e.air_date, f.name,
                w.series_id IS NOT NULL
         FROM episodes e JOIN following f ON f.id = e.series_id {WATCHED_JOIN}
         LEFT JOIN notified n ON n.series_id = e.series_id AND n.season = e.season
             AND n.number = e.number AND n.air_date = e.air_date
         WHERE e.air_date BETWEEN ? AND ?
           AND (? = 0 OR (w.series_id IS NULL AND n.series_id IS NULL))
         ORDER BY e.air_date, f.name COLLATE NOCASE, e.series_id, e.season, e.number"
    ))?;
    let results = stmt
        .query_map(params![start, end, only_pending], |row| {
            Ok(Premiere {
                episode: read_episode(row)?,
                series_name: row.get(6)?,
                watched: row.get(7)?,
            })
        })?
        .collect::<rusqlite::Result<_>>()?;
    Ok(results)
}

fn replace_catalog(tx: &Transaction<'_>, catalog: &Catalog, today: Option<&str>) -> Result<()> {
    let series = &catalog.series;
    let poster = series.poster_url.as_deref().and_then(clean_poster_url);
    let poster = poster
        .as_deref()
        .or_else(|| today.filter(|_| is_tvmaze(series.id)).map(|_| ""));
    tx.execute(
        "INSERT INTO following(id, name, overview, first_air_date, last_refresh, poster_url, archived)
         VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(id) DO UPDATE SET name=excluded.name,
         overview=excluded.overview, first_air_date=excluded.first_air_date,
         last_refresh=excluded.last_refresh, poster_url=excluded.poster_url",
        params![series.id, series.name, series.overview, series.first_air_date, today, poster, catalog.archived],
    )?;
    tx.execute("DELETE FROM episodes WHERE series_id = ?", [series.id])?;
    insert_episodes(tx, catalog)
}

fn merge_catalog(tx: &Transaction<'_>, catalog: &Catalog) -> Result<()> {
    let series = &catalog.series;
    let poster = series.poster_url.as_deref().and_then(clean_poster_url);
    tx.execute(
        "INSERT INTO following(id, name, overview, first_air_date, poster_url, archived)
         VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(id) DO UPDATE
         SET poster_url=COALESCE(NULLIF(following.poster_url, ''), excluded.poster_url)",
        params![
            series.id,
            series.name,
            series.overview,
            series.first_air_date,
            poster,
            catalog.archived
        ],
    )?;
    insert_episodes(tx, catalog)
}

fn insert_episodes(tx: &Transaction<'_>, catalog: &Catalog) -> Result<()> {
    let mut stmt = tx.prepare_cached(
        "INSERT OR IGNORE INTO episodes(series_id, season, number, name, overview, air_date)
         VALUES (?, ?, ?, ?, ?, ?)",
    )?;
    for episode in &catalog.episodes {
        stmt.execute(params![
            episode.series_id,
            episode.season,
            episode.number,
            episode.name,
            episode.overview,
            episode.air_date
        ])?;
    }
    Ok(())
}

fn decode_backup(data: &[u8]) -> Result<Backup> {
    if data.len() > MAX_BACKUP_BYTES {
        return Err(CoreError::FileTooLarge);
    }
    let header: BackupHeader =
        serde_json::from_slice(data).map_err(|_| CoreError::InvalidBackup)?;
    if header.schema_version != SCHEMA_VERSION {
        return Err(CoreError::BackupVersion);
    }
    let mut backup: Backup = serde_json::from_slice(data).map_err(|_| CoreError::InvalidBackup)?;
    backup.settings = validated_settings(backup.settings).map_err(|_| CoreError::InvalidBackup)?;
    let mut seen = HashSet::new();
    for catalog in &backup.following {
        if !seen.insert(catalog.series.id) || catalog.validate().is_err() {
            return Err(CoreError::InvalidBackup);
        }
    }
    if backup
        .watched
        .iter()
        .any(|key| key.number == 0 || !seen.contains(&key.series_id))
    {
        return Err(CoreError::InvalidBackup);
    }
    Ok(backup)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{Language, ThemeMode, ThemeSettings};

    const TODAY: &str = "2026-11-21";
    const KEY: EpisodeKey = EpisodeKey {
        series_id: 1,
        season: 1,
        number: 1,
    };

    fn catalog(id: u32) -> Catalog {
        Catalog {
            series: Series {
                id,
                name: format!("Series {id}"),
                overview: String::new(),
                first_air_date: None,
                catalog_url: catalog_url(id),
                poster_url: None,
            },
            episodes: vec![Episode {
                series_id: id,
                season: 1,
                number: 1,
                name: "Pilot".into(),
                overview: String::new(),
                air_date: Some(TODAY.into()),
            }],
            archived: false,
        }
    }

    #[test]
    fn migration_is_idempotent_and_rejects_future_versions() -> Result<()> {
        let mut conn = Connection::open_in_memory()?;
        migrate(&mut conn)?;
        migrate(&mut conn)?;
        assert_eq!(read_settings(&conn)?, Settings::default());
        conn.pragma_update(None, "user_version", DB_VERSION + 1)?;
        assert_eq!(migrate(&mut conn), Err(CoreError::DatabaseVersion));
        Ok(())
    }

    #[test]
    fn upgrade_keeps_followed_titles_and_watch_history() -> Result<()> {
        let mut conn = Connection::open_in_memory()?;
        conn.execute_batch(SCHEMA_V1)?;
        conn.pragma_update(None, "user_version", 1)?;
        conn.execute(
            "INSERT INTO following(id, name, overview) VALUES (1, 'Example', '')",
            [],
        )?;
        conn.execute(
            "INSERT INTO watched(series_id, season, number) VALUES (1, 1, 2)",
            [],
        )?;
        migrate(&mut conn)?;
        let store = Store(Mutex::new(conn));
        let dashboard = store.dashboard(TODAY, TODAY)?;
        assert_eq!(dashboard.following[0].series.name, "Example");
        assert!(!dashboard.following[0].archived);
        assert_eq!(store.poster_url(1)?, Some(None));
        assert_eq!(decode_backup(&store.export_backup()?)?.watched.len(), 1);
        Ok(())
    }

    #[test]
    fn marking_every_episode_is_idempotent_and_enables_archiving() -> Result<()> {
        use crate::{filter_following, FollowingFilter};
        let store = Store::open(":memory:".into())?;
        let mut show = catalog(1);
        let mut other_season = show.episodes[0].clone();
        other_season.season = 2;
        other_season.air_date = Some("2026-12-31".into());
        show.episodes.push(other_season);
        store.save_catalog(&show, TODAY, true)?;
        store.save_catalog(&catalog(2), TODAY, true)?;
        assert_eq!(
            store.set_archived(1, true),
            Err(CoreError::IncompleteSeries)
        );
        store.set_watched(&KEY, true)?;
        let entries = store.dashboard(TODAY, TODAY)?.following;
        assert_eq!(
            filter_following(entries, "sErIeS 1".into(), FollowingFilter::InProgress).len(),
            1
        );
        store.mark_episodes_watched(1, None)?;
        store.mark_episodes_watched(1, None)?;
        assert!(store.episodes(1)?.iter().all(|state| state.watched));
        assert!(!store.episodes(2)?[0].watched);
        store.set_archived(1, true)?;
        let entries = store.dashboard(TODAY, TODAY)?.following;
        assert_eq!(
            filter_following(entries.clone(), String::new(), FollowingFilter::Active).len(),
            1
        );
        assert_eq!(
            filter_following(entries, String::new(), FollowingFilter::Archived)[0]
                .series
                .id,
            1
        );
        let target = Store::open(":memory:".into())?;
        target.import_backup(&store.export_backup()?, false)?;
        assert!(target.dashboard(TODAY, TODAY)?.following[0].archived);
        target.set_archived(1, false)?;
        assert!(!target.dashboard(TODAY, TODAY)?.following[0].archived);
        Ok(())
    }

    #[test]
    fn marking_a_season_only_changes_its_episodes_and_supports_specials() -> Result<()> {
        let store = Store::open(":memory:".into())?;
        let mut show = catalog(1);
        let episode = show.episodes[0].clone();
        show.episodes.push(Episode {
            season: 0,
            ..episode.clone()
        });
        show.episodes.push(Episode {
            season: 2,
            ..episode
        });
        store.save_catalog(&show, TODAY, true)?;
        store.save_catalog(&catalog(2), TODAY, true)?;

        store.mark_episodes_watched(1, Some(0))?;
        store.mark_episodes_watched(1, Some(0))?;
        store.mark_episodes_watched(1, Some(2))?;
        store.mark_episodes_watched(1, Some(99))?;
        for entry in store.episodes(1)? {
            assert_eq!(entry.watched, matches!(entry.episode.season, 0 | 2));
        }
        assert!(store.episodes(2)?.iter().all(|entry| !entry.watched));
        assert_eq!(decode_backup(&store.export_backup()?)?.watched.len(), 2);
        assert_eq!(
            store.set_archived(1, true),
            Err(CoreError::IncompleteSeries)
        );
        Ok(())
    }

    #[test]
    fn clearing_watch_marks_respects_scope_and_clears_unlisted_episodes() -> Result<()> {
        let store = Store::open(":memory:".into())?;
        let mut show = catalog(1);
        let episode = show.episodes[0].clone();
        show.episodes.push(Episode {
            season: 0,
            ..episode.clone()
        });
        show.episodes.push(Episode {
            season: 2,
            ..episode
        });
        store.save_catalog(&show, TODAY, true)?;
        store.save_catalog(&catalog(2), TODAY, true)?;
        store.mark_episodes_watched(1, None)?;
        store.mark_episodes_watched(2, None)?;
        store.set_archived(1, true)?;

        show.episodes.retain(|episode| episode.season == 1);
        store.save_catalog(&show, TODAY, false)?;
        assert_eq!(store.watched_seasons(1)?, vec![0, 1, 2]);

        store.mark_episodes_unwatched(1, Some(0))?;
        store.mark_episodes_unwatched(1, Some(0))?;
        assert_eq!(store.watched_seasons(1)?, vec![1, 2]);
        assert!(store.episodes(1)?[0].watched);

        store.mark_episodes_unwatched(1, None)?;
        store.mark_episodes_unwatched(1, None)?;
        assert!(store.watched_seasons(1)?.is_empty());
        assert!(!store.episodes(1)?[0].watched);
        assert!(store.episodes(2)?[0].watched);
        assert!(store.dashboard(TODAY, TODAY)?.following[0].archived);
        let backup = decode_backup(&store.export_backup()?)?;
        assert_eq!(
            backup.watched,
            vec![EpisodeKey {
                series_id: 2,
                ..KEY
            }]
        );
        Ok(())
    }

    #[test]
    fn backfills_only_unknown_posters_and_preserves_them_in_backups() -> Result<()> {
        let store = Store::open(":memory:".into())?;
        let poster = "https://static.tvmaze.com/uploads/images/medium_portrait/1/2.jpg";
        let id = ID_NAMESPACE + 1;
        let mut show = catalog(id);
        show.series.poster_url = Some(poster.into());
        store.save_catalog(&show, TODAY, true)?;
        store.save_poster_if_unknown(id, None)?;
        assert_eq!(store.poster_url(id)?, Some(Some(poster.into())));
        let restored = Store::open(":memory:".into())?;
        restored.import_backup(&store.export_backup()?, false)?;
        assert_eq!(
            restored.dashboard(TODAY, TODAY)?.following[0]
                .series
                .poster_url
                .as_deref(),
            Some(poster)
        );
        Ok(())
    }

    #[test]
    fn premieres_respect_date_watched_and_notification_state() -> Result<()> {
        let store = Store::open(":memory:".into())?;
        store.save_catalog(&catalog(1), TODAY, true)?;
        assert_eq!(store.premieres(TODAY, TODAY, true)?.len(), 1);
        assert!(store
            .premieres("2026-11-22", "2026-11-22", true)?
            .is_empty());
        store.set_watched(&KEY, true)?;
        assert!(store.premieres(TODAY, TODAY, true)?.is_empty());
        assert!(store.dashboard(TODAY, TODAY)?.upcoming[0].watched);
        store.set_watched(&KEY, false)?;
        store.mark_notified(&KEY, TODAY)?;
        store.mark_notified(&KEY, TODAY)?;
        assert!(store.premieres(TODAY, TODAY, true)?.is_empty());
        assert_eq!(store.premieres(TODAY, TODAY, false)?.len(), 1);
        Ok(())
    }

    #[test]
    fn refresh_preserves_watch_history_when_the_catalog_removes_an_episode() -> Result<()> {
        let store = Store::open(":memory:".into())?;
        let mut data = catalog(1);
        store.save_catalog(&data, TODAY, true)?;
        store.set_watched(&KEY, true)?;
        assert!(store.due(TODAY)?.is_empty());
        assert!(store.due("2026-11-22")?.is_empty());
        let episode = data.episodes.remove(0);
        store.save_catalog(&data, TODAY, false)?;
        assert!(store.episodes(1)?.is_empty());
        assert_eq!(store.dashboard(TODAY, TODAY)?.following[0].watched_count, 0);
        data.episodes.push(episode);
        store.save_catalog(&data, TODAY, false)?;
        assert!(store.episodes(1)?[0].watched);
        store.unfollow(1)?;
        store.save_catalog(&data, TODAY, false)?;
        assert!(store.dashboard(TODAY, TODAY)?.following.is_empty());
        Ok(())
    }

    #[test]
    fn relinking_unifies_progress_and_keeps_unmatched_keys_in_backup() -> Result<()> {
        let store = Store::open(":memory:".into())?;
        let mut legacy = catalog(1);
        let mut second = legacy.episodes[0].clone();
        second.number = 2;
        legacy.episodes.push(second);
        store.save_catalog(&legacy, TODAY, true)?;
        store.set_watched(&KEY, true)?;
        store.set_watched(&EpisodeKey { number: 2, ..KEY }, true)?;
        store.mark_notified(&KEY, TODAY)?;
        assert!(store.due("2026-11-22")?.is_empty());

        let id = ID_NAMESPACE + 1;
        let remote = catalog(id);
        assert_eq!(store.relink(2, &remote, TODAY), Err(CoreError::InvalidItem));
        assert_eq!(store.dashboard(TODAY, TODAY)?.following.len(), 1);
        store.save_catalog(&remote, TODAY, true)?;
        store.relink(1, &remote, TODAY)?;
        let dashboard = store.dashboard(TODAY, TODAY)?;
        assert_eq!(dashboard.following.len(), 1);
        assert_eq!(dashboard.following[0].series.id, id);
        assert_eq!(dashboard.following[0].watched_count, 1);
        assert!(store.episodes(1)?.is_empty());
        assert_eq!(store.due("2026-11-22")?, vec![id]);

        let backup = decode_backup(&store.export_backup()?)?;
        assert_eq!(backup.watched.len(), 2);
        assert!(backup.watched.iter().all(|key| key.series_id == id));
        assert_eq!(backup.catalog_attribution.as_deref(), Some(CREDIT));
        assert_eq!(store.relink(1, &remote, TODAY), Err(CoreError::InvalidItem));
        Ok(())
    }

    #[test]
    fn all_interface_languages_survive_backup_and_restore() -> Result<()> {
        let source = Store::open(":memory:".into())?;
        let target = Store::open(":memory:".into())?;
        for (language, tag) in [
            (Language::En, "en"),
            (Language::Cs, "cs"),
            (Language::Eo, "eo"),
            (Language::Ko, "ko"),
            (Language::Uk, "uk"),
            (Language::Tlh, "tlh"),
        ] {
            source.set_settings(&Settings {
                language,
                ..Settings::default()
            })?;
            let backup = source.export_backup()?;
            let json: serde_json::Value =
                serde_json::from_slice(&backup).map_err(|_| CoreError::InvalidBackup)?;
            assert_eq!(json["settings"]["language"], tag);
            target.import_backup(&backup, false)?;
            assert_eq!(target.settings()?.language, language);
        }
        Ok(())
    }

    #[test]
    fn backup_round_trip_merge_replace_and_validation_are_atomic() -> Result<()> {
        let source = Store::open(":memory:".into())?;
        source.save_catalog(&catalog(1), TODAY, true)?;
        source.set_watched(&KEY, true)?;
        let settings = Settings {
            language: Language::Cs,
            theme: ThemeSettings {
                mode: ThemeMode::Amoled,
                accent_hex: "#0079A8".into(),
            },
            navigation_order: crate::DEFAULT_NAVIGATION_ORDER.into_iter().rev().collect(),
            notifications_enabled: false,
        };
        source.set_settings(&settings)?;
        let backup = source.export_backup()?;
        let target = Store::open(":memory:".into())?;
        target.save_catalog(&catalog(2), TODAY, true)?;
        target.import_backup(&backup, true)?;
        target.import_backup(&backup, true)?;
        assert_eq!(target.dashboard(TODAY, TODAY)?.following.len(), 2);
        assert_eq!(target.settings()?, Settings::default());
        assert!(target.episodes(1)?[0].watched);
        assert_eq!(
            target.import_backup(br#"{"schema_version":2}"#, false),
            Err(CoreError::BackupVersion)
        );
        assert_eq!(
            target.import_backup(b"{", false),
            Err(CoreError::InvalidBackup)
        );
        assert_eq!(target.dashboard(TODAY, TODAY)?.following.len(), 2);
        target.import_backup(&backup, false)?;
        assert_eq!(target.settings()?, settings);
        assert_eq!(target.export_backup()?, backup);
        Ok(())
    }
}
