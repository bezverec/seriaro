mod http;
mod store;
mod tvmaze;
mod webdav;

use std::path::PathBuf;

use chrono::{Days, NaiveDate};
use serde::{Deserialize, Serialize};

uniffi::setup_scaffolding!();

type Result<T> = std::result::Result<T, CoreError>;

#[derive(Debug, thiserror::Error, uniffi::Error, PartialEq, Eq)]
pub enum CoreError {
    #[error("The local database is unavailable")]
    Database,
    #[error("This database version is unsupported")]
    DatabaseVersion,
    #[error("Invalid series or episode")]
    InvalidItem,
    #[error("Only fully watched series can be archived")]
    IncompleteSeries,
    #[error("Date must be in YYYY-MM-DD format")]
    InvalidDate,
    #[error("The accent must contain six hexadecimal digits")]
    InvalidAccent,
    #[error("The backup is invalid")]
    InvalidBackup,
    #[error("The backup version is unsupported")]
    BackupVersion,
    #[error("The server rejected the credentials")]
    Unauthorized,
    #[error("The server is temporarily unavailable")]
    Network,
    #[error("The server response was unsuccessful")]
    Remote,
    #[error("Enter an HTTPS URL for the WebDAV backup file")]
    InvalidWebDavUrl,
    #[error("Request exceeded the supported data size")]
    FileTooLarge,
}

impl From<rusqlite::Error> for CoreError {
    fn from(_: rusqlite::Error) -> Self {
        Self::Database
    }
}

#[derive(Clone, Copy, Debug, Serialize, Deserialize, PartialEq, Eq, uniffi::Enum)]
#[serde(rename_all = "snake_case")]
pub enum ThemeMode {
    System,
    Light,
    Dark,
    Amoled,
}

#[derive(Clone, Copy, Debug, Default, Serialize, Deserialize, PartialEq, Eq, uniffi::Enum)]
#[serde(rename_all = "snake_case")]
pub enum Language {
    #[default]
    En,
    Cs,
    Eo,
    Ko,
    Uk,
    Tlh,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq, uniffi::Record)]
pub struct ThemeSettings {
    pub mode: ThemeMode,
    pub accent_hex: String,
}

impl Default for ThemeSettings {
    fn default() -> Self {
        Self {
            mode: ThemeMode::System,
            accent_hex: "#F97316".into(),
        }
    }
}

#[derive(Clone, Debug, Default, Serialize, Deserialize, PartialEq, Eq, uniffi::Record)]
pub struct Settings {
    pub theme: ThemeSettings,
    pub language: Language,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq, uniffi::Record)]
pub struct Series {
    pub id: u32,
    pub name: String,
    pub overview: String,
    pub first_air_date: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub catalog_url: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub poster_url: Option<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq, uniffi::Record)]
pub struct Episode {
    pub series_id: u32,
    pub season: u32,
    pub number: u32,
    pub name: String,
    pub overview: String,
    pub air_date: Option<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq, uniffi::Record)]
pub struct EpisodeState {
    pub episode: Episode,
    pub watched: bool,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct SeriesProgress {
    pub series: Series,
    pub watched_count: u32,
    pub episode_count: u32,
    pub archived: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum FollowingFilter {
    Active,
    InProgress,
    Completed,
    Archived,
    All,
}

#[uniffi::export]
pub fn filter_following(
    entries: Vec<SeriesProgress>,
    query: String,
    filter: FollowingFilter,
) -> Vec<SeriesProgress> {
    let query = query.trim().to_lowercase();
    entries
        .into_iter()
        .filter(|entry| {
            let complete = entry.episode_count > 0 && entry.watched_count == entry.episode_count;
            let matches_filter = match filter {
                FollowingFilter::Active => !entry.archived,
                FollowingFilter::InProgress => {
                    !entry.archived && entry.watched_count > 0 && !complete
                }
                FollowingFilter::Completed => !entry.archived && complete,
                FollowingFilter::Archived => entry.archived,
                FollowingFilter::All => true,
            };
            matches_filter
                && (query.is_empty() || entry.series.name.to_lowercase().contains(&query))
        })
        .collect()
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct Premiere {
    pub series_name: String,
    pub episode: Episode,
    pub watched: bool,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct Dashboard {
    pub settings: Settings,
    pub following: Vec<SeriesProgress>,
    pub upcoming: Vec<Premiere>,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq, uniffi::Record)]
pub struct EpisodeKey {
    pub series_id: u32,
    pub season: u32,
    pub number: u32,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
struct Catalog {
    series: Series,
    episodes: Vec<Episode>,
    #[serde(default, skip_serializing_if = "is_false")]
    archived: bool,
}

fn is_false(value: &bool) -> bool {
    !*value
}

impl Catalog {
    fn validate(&self) -> Result<()> {
        let valid_series = self.series.id > 0
            && !self.series.name.trim().is_empty()
            && valid_air_date(&self.series.first_air_date);
        let mut seen = std::collections::HashSet::new();
        let valid_episodes = self.episodes.iter().all(|episode| {
            episode.series_id == self.series.id
                && episode.number > 0
                && valid_air_date(&episode.air_date)
                && seen.insert((episode.season, episode.number))
        });
        if valid_series && valid_episodes {
            Ok(())
        } else {
            Err(CoreError::InvalidItem)
        }
    }
}

#[derive(uniffi::Object)]
pub struct Tracker {
    store: store::Store,
    catalog: tvmaze::Client,
}

#[uniffi::export]
impl Tracker {
    #[uniffi::constructor]
    pub fn open(database_path: String) -> Result<Self> {
        Ok(Self {
            store: store::Store::open(PathBuf::from(database_path))?,
            catalog: tvmaze::Client::new()?,
        })
    }

    pub fn dashboard(&self, today: String) -> Result<Dashboard> {
        let date = parse_date(&today)?;
        let end = date
            .checked_add_days(Days::new(90))
            .ok_or(CoreError::InvalidDate)?;
        self.store.dashboard(&today, &end.to_string())
    }

    pub fn settings(&self) -> Result<Settings> {
        self.store.settings()
    }

    pub fn set_settings(&self, settings: Settings) -> Result<()> {
        self.store.set_settings(&validated_settings(settings)?)
    }

    pub fn search(&self, query: String) -> Result<Vec<Series>> {
        self.catalog.search(query.trim())
    }

    pub fn follow(&self, id: u32, today: String) -> Result<()> {
        parse_date(&today)?;
        let catalog = self.catalog.catalog(id)?;
        self.store.save_catalog(&catalog, &today, true)
    }

    pub fn relink(&self, legacy_id: u32, tvmaze_id: u32, today: String) -> Result<()> {
        parse_date(&today)?;
        if legacy_id == 0 || legacy_id >= tvmaze::ID_NAMESPACE {
            return Err(CoreError::InvalidItem);
        }
        let catalog = self.catalog.catalog(tvmaze_id)?;
        self.store.relink(legacy_id, &catalog, &today)
    }

    pub fn unfollow(&self, id: u32) -> Result<()> {
        self.store.unfollow(id)
    }

    pub fn episodes(&self, id: u32) -> Result<Vec<EpisodeState>> {
        self.store.episodes(id)
    }

    pub fn set_watched(&self, key: EpisodeKey, watched: bool) -> Result<()> {
        self.store.set_watched(&key, watched)
    }

    pub fn set_archived(&self, id: u32, archived: bool) -> Result<()> {
        self.store.set_archived(id, archived)
    }

    pub fn mark_all_watched(&self, id: u32) -> Result<()> {
        self.store.mark_all_watched(id)
    }

    pub fn poster_for_series(&self, id: u32, url: Option<String>) -> Result<Option<Vec<u8>>> {
        if !tvmaze::is_tvmaze(id) {
            return Ok(None);
        }
        let url = match url {
            Some(url) => Some(url),
            None => match self.store.poster_url(id)? {
                Some(Some(url)) => tvmaze::clean_poster_url(&url),
                Some(None) => {
                    let url = self.catalog.lookup_poster(id)?;
                    self.store.save_poster_if_unknown(id, url.as_deref())?;
                    url
                }
                None => None,
            },
        };
        url.map(|url| self.catalog.poster(&url)).transpose()
    }

    pub fn refresh(&self, id: u32, today: String) -> Result<()> {
        parse_date(&today)?;
        self.refresh_catalog(id, &today)
    }

    pub fn refresh_due(&self, today: String) -> Result<()> {
        parse_date(&today)?;
        let mut first_error = None;
        for id in self.store.due(&today)? {
            if let Err(error) = self.refresh_catalog(id, &today) {
                if matches!(error, CoreError::Network) {
                    return Err(error);
                }
                first_error.get_or_insert(error);
            }
        }
        match first_error {
            Some(error) => Err(error),
            None => Ok(()),
        }
    }

    pub fn check_todays_premieres(&self, today: String) -> Result<Vec<Premiere>> {
        parse_date(&today)?;
        self.store.premieres(&today, &today, true)
    }

    pub fn mark_notified(&self, key: EpisodeKey, today: String) -> Result<()> {
        parse_date(&today)?;
        self.store.mark_notified(&key, &today)
    }

    pub fn export_backup(&self) -> Result<Vec<u8>> {
        self.store.export_backup()
    }

    pub fn import_backup(&self, data: Vec<u8>, merge: bool) -> Result<()> {
        self.store.import_backup(&data, merge)
    }

    pub fn upload_webdav(&self, url: String, username: String, password: String) -> Result<()> {
        webdav::upload(&url, &username, &password, self.export_backup()?)
    }

    pub fn download_webdav(
        &self,
        url: String,
        username: String,
        password: String,
        merge: bool,
    ) -> Result<()> {
        let data = webdav::download(&url, &username, &password)?;
        self.import_backup(data, merge)
    }
}

impl Tracker {
    fn refresh_catalog(&self, id: u32, today: &str) -> Result<()> {
        let catalog = self.catalog.catalog(id)?;
        self.store.save_catalog(&catalog, today, false)
    }
}

fn parse_date(value: &str) -> Result<NaiveDate> {
    let date = NaiveDate::parse_from_str(value, "%Y-%m-%d").map_err(|_| CoreError::InvalidDate)?;
    if date.to_string() == value && value.len() == 10 && !value.starts_with("0000") {
        Ok(date)
    } else {
        Err(CoreError::InvalidDate)
    }
}

fn valid_air_date(date: &Option<String>) -> bool {
    date.as_deref().is_none_or(|date| parse_date(date).is_ok())
}

fn clean_air_date(date: Option<String>) -> Option<String> {
    date.filter(|date| parse_date(date).is_ok())
}

#[uniffi::export]
pub fn normalize_accent(value: String) -> Result<String> {
    let value = value.trim();
    let digits = value.strip_prefix('#').unwrap_or(value);
    if digits.len() == 6 && digits.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        Ok(format!("#{}", digits.to_ascii_uppercase()))
    } else {
        Err(CoreError::InvalidAccent)
    }
}

fn validated_settings(mut settings: Settings) -> Result<Settings> {
    settings.theme.accent_hex = normalize_accent(settings.theme.accent_hex)?;
    Ok(settings)
}

#[uniffi::export]
pub fn accent_uses_dark_text(value: String) -> Result<bool> {
    let hex = normalize_accent(value)?;
    let rgb = u32::from_str_radix(&hex[1..], 16).map_err(|_| CoreError::InvalidAccent)?;
    let linear = |shift: u32| {
        let srgb = f64::from((rgb >> shift) & 255) / 255.0;
        if srgb <= 0.04045 {
            srgb / 12.92
        } else {
            ((srgb + 0.055) / 1.055).powf(2.4)
        }
    };
    let luminance = 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0);
    Ok((luminance + 0.05) / 0.05 >= 1.05 / (luminance + 0.05))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn validates_accent_and_chooses_maximum_contrast() -> Result<()> {
        assert_eq!(normalize_accent("  f97316 ".into())?, "#F97316");
        assert_eq!(
            normalize_accent("#123".into()),
            Err(CoreError::InvalidAccent)
        );
        assert_eq!(
            normalize_accent("#00XX00".into()),
            Err(CoreError::InvalidAccent)
        );
        assert!(accent_uses_dark_text("#F97316".into())?);
        assert!(accent_uses_dark_text("#FFFFFF".into())?);
        assert!(!accent_uses_dark_text("#000000".into())?);
        assert!(accent_uses_dark_text("#777777".into())?);
        assert!(!accent_uses_dark_text("#757575".into())?);
        Ok(())
    }

    #[test]
    fn validates_canonical_dates_and_leap_days() {
        assert!(parse_date("2024-02-29").is_ok());
        assert!(parse_date("2025-02-29").is_err());
        assert!(parse_date("2026-2-01").is_err());
        assert!(parse_date("0000-01-01").is_err());
    }
}
