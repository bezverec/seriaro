use std::collections::{HashSet, VecDeque};
use std::io::Read;
use std::sync::Mutex;

use reqwest::{blocking::Client as HttpClient, header, Url};
use serde::{de::DeserializeOwned, Deserialize};

use crate::http::{check_status, http_client};
use crate::{clean_air_date, Catalog, CoreError, Episode, Result, Series};

const API: &str = "https://api.tvmaze.com";
const MAX_POSTER_BYTES: usize = 512 * 1024;
const CACHED_POSTERS: usize = 64;
// Previously stored TMDB identifiers are signed 32-bit API IDs. Keep that range
// reserved, including in version 1 backups, so IDs from both sources cannot collide.
pub(crate) const ID_NAMESPACE: u32 = 1 << 31;
pub(crate) const CREDIT: &str = "TVmaze (https://www.tvmaze.com/), CC BY-SA 4.0 (https://creativecommons.org/licenses/by-sa/4.0/). HTML summaries converted to plain text.";

pub(crate) fn is_tvmaze(id: u32) -> bool {
    id > ID_NAMESPACE
}

fn local_id(id: u32) -> Option<u32> {
    (1..ID_NAMESPACE).contains(&id).then_some(ID_NAMESPACE | id)
}

pub(crate) struct Client {
    http: HttpClient,
    posters: Mutex<VecDeque<(String, Vec<u8>)>>,
}

#[derive(Deserialize)]
struct SearchHit {
    show: Show,
}

#[derive(Deserialize)]
struct Show {
    id: u32,
    name: String,
    premiered: Option<String>,
    summary: Option<String>,
    image: Option<ShowImage>,
    #[serde(rename = "_embedded")]
    embedded: Option<Embedded>,
}

#[derive(Deserialize)]
struct ShowImage {
    medium: Option<String>,
}

#[derive(Deserialize)]
struct PosterDetails {
    image: Option<ShowImage>,
}

#[derive(Deserialize)]
struct Embedded {
    episodes: Vec<ShowEpisode>,
}

#[derive(Deserialize)]
struct ShowEpisode {
    season: Option<u32>,
    number: Option<u32>,
    name: Option<String>,
    airdate: Option<String>,
    summary: Option<String>,
}

impl Client {
    pub(crate) fn new() -> Result<Self> {
        Ok(Self {
            http: http_client()?,
            posters: Mutex::new(VecDeque::new()),
        })
    }

    fn get<T: DeserializeOwned>(&self, path: &str, query: &[(&str, &str)]) -> Result<T> {
        let response = self
            .http
            .get(format!("{API}{path}"))
            .header(
                header::USER_AGENT,
                concat!("Seriaro/", env!("CARGO_PKG_VERSION")),
            )
            .query(query)
            .send()
            .map_err(|_| CoreError::Network)?;
        check_status(response.status())?;
        response.json().map_err(|error| {
            if error.is_timeout() || error.is_body() {
                CoreError::Network
            } else {
                CoreError::Remote
            }
        })
    }

    pub(crate) fn search(&self, query: &str) -> Result<Vec<Series>> {
        if query.is_empty() {
            return Ok(Vec::new());
        }
        let hits: Vec<SearchHit> = self.get("/search/shows", &[("q", query)])?;
        let mut seen = HashSet::new();
        Ok(hits
            .into_iter()
            .filter_map(|hit| hit.show.into_series())
            .filter(|series| seen.insert(series.id))
            .collect())
    }

    pub(crate) fn lookup_poster(&self, id: u32) -> Result<Option<String>> {
        let path = format!("/shows/{}", id & !ID_NAMESPACE);
        let details: PosterDetails = self.get(&path, &[])?;
        Ok(details.image.and_then(ShowImage::poster_url))
    }

    pub(crate) fn poster(&self, url: &str) -> Result<Vec<u8>> {
        let url = clean_poster_url(url).ok_or(CoreError::InvalidItem)?;
        {
            let mut posters = self.posters.lock().map_err(|_| CoreError::Network)?;
            let index = posters.iter().position(|(key, _)| key == &url);
            if let Some(entry) = index.and_then(|index| posters.remove(index)) {
                let bytes = entry.1.clone();
                posters.push_front(entry);
                return Ok(bytes);
            }
        }
        let response = self
            .http
            .get(&url)
            .header(
                header::USER_AGENT,
                concat!("Seriaro/", env!("CARGO_PKG_VERSION")),
            )
            .send()
            .map_err(|_| CoreError::Network)?;
        check_status(response.status())?;
        let mime = response
            .headers()
            .get(header::CONTENT_TYPE)
            .and_then(|header| header.to_str().ok())
            .and_then(|value| value.split(';').next());
        if !matches!(mime, Some("image/jpeg" | "image/png" | "image/webp")) {
            return Err(CoreError::Remote);
        }
        let bytes = read_poster(response)?;
        let mut posters = self.posters.lock().map_err(|_| CoreError::Network)?;
        posters.retain(|(key, _)| key != &url);
        posters.push_front((url, bytes.clone()));
        posters.truncate(CACHED_POSTERS);
        Ok(bytes)
    }

    pub(crate) fn catalog(&self, id: u32) -> Result<Catalog> {
        if !is_tvmaze(id) {
            return Err(CoreError::InvalidItem);
        }
        let path = format!("/shows/{}", id & !ID_NAMESPACE);
        let mut show: Show = self.get(&path, &[("embed", "episodes")])?;
        if show.embedded.is_none() {
            let episodes = self.get(&format!("{path}/episodes"), &[])?;
            show.embedded = Some(Embedded { episodes });
        }
        show.into_catalog(id)
    }
}

impl Show {
    fn into_series(self) -> Option<Series> {
        let id = local_id(self.id)?;
        let name = self.name.trim();
        if name.is_empty() {
            return None;
        }
        Some(Series {
            id,
            name: name.to_owned(),
            overview: plain_summary(self.summary.as_deref()),
            first_air_date: clean_air_date(self.premiered),
            catalog_url: catalog_url(id),
            poster_url: self.image.and_then(ShowImage::poster_url),
        })
    }

    fn into_catalog(mut self, expected_id: u32) -> Result<Catalog> {
        let episode_list = self.embedded.take().ok_or(CoreError::Remote)?;
        let series = self.into_series().ok_or(CoreError::InvalidItem)?;
        if series.id != expected_id {
            return Err(CoreError::Remote);
        }
        let mut seen = HashSet::new();
        let episodes = episode_list
            .episodes
            .into_iter()
            .filter_map(|episode| {
                let season = episode.season?;
                let number = episode.number.filter(|number| *number > 0)?;
                if !seen.insert((season, number)) {
                    return None;
                }
                Some(Episode {
                    series_id: series.id,
                    season,
                    number,
                    name: episode.name.unwrap_or_default(),
                    overview: plain_summary(episode.summary.as_deref()),
                    air_date: clean_air_date(episode.airdate),
                })
            })
            .collect();
        let catalog = Catalog {
            series,
            episodes,
            archived: false,
        };
        catalog.validate()?;
        Ok(catalog)
    }
}

impl ShowImage {
    fn poster_url(self) -> Option<String> {
        self.medium.as_deref().and_then(clean_poster_url)
    }
}

pub(crate) fn clean_poster_url(value: &str) -> Option<String> {
    let url = Url::parse(value).ok()?;
    if url.scheme() != "https"
        || url.host_str() != Some("static.tvmaze.com")
        || url.port().is_some()
        || !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
    {
        return None;
    }
    let suffix = url
        .path()
        .strip_prefix("/uploads/images/medium_portrait/")?;
    let (directory, file) = suffix.split_once('/')?;
    let (name, extension) = file.split_once('.')?;
    let is_number = |part: &str| !part.is_empty() && part.bytes().all(|byte| byte.is_ascii_digit());
    (is_number(directory)
        && is_number(name)
        && matches!(extension, "jpg" | "jpeg" | "png" | "webp"))
    .then(|| url.into())
}

fn read_poster(body: impl Read) -> Result<Vec<u8>> {
    let mut bytes = Vec::new();
    body.take(MAX_POSTER_BYTES as u64 + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| CoreError::Network)?;
    if bytes.is_empty() || bytes.len() > MAX_POSTER_BYTES {
        return Err(CoreError::Remote);
    }
    Ok(bytes)
}

pub(crate) fn catalog_url(id: u32) -> Option<String> {
    is_tvmaze(id).then(|| format!("https://www.tvmaze.com/shows/{}", id & !ID_NAMESPACE))
}

fn plain_summary(summary: Option<&str>) -> String {
    summary
        .and_then(|html| {
            html2text::config::plain_no_decorate()
                .string_from_read(html.as_bytes(), 512)
                .ok()
        })
        .map(|text| text.trim().into())
        .unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn separates_legacy_ids_and_validates_tvmaze_ids() {
        assert!(!is_tvmaze(82));
        assert!(!is_tvmaze(ID_NAMESPACE));
        assert_eq!(local_id(82), Some(ID_NAMESPACE + 82));
        assert_eq!(local_id(0), None);
        assert_eq!(local_id(ID_NAMESPACE), None);
        assert_eq!(
            catalog_url(ID_NAMESPACE + 82).as_deref(),
            Some("https://www.tvmaze.com/shows/82")
        );
    }

    #[test]
    fn restricts_posters_to_medium_cdn_files_and_bounds_downloads() -> Result<()> {
        let valid = "https://static.tvmaze.com/uploads/images/medium_portrait/1/2.jpg";
        assert_eq!(clean_poster_url(valid).as_deref(), Some(valid));
        for invalid in [
            "http://static.tvmaze.com/uploads/images/medium_portrait/1/2.jpg",
            "https://static.tvmaze.com.evil.example/uploads/images/medium_portrait/1/2.jpg",
            "https://static.tvmaze.com:8443/uploads/images/medium_portrait/1/2.jpg",
            "https://user@static.tvmaze.com/uploads/images/medium_portrait/1/2.jpg",
            "https://static.tvmaze.com/uploads/images/original_untouched/1/2.jpg",
            "https://static.tvmaze.com/uploads/images/medium_portrait/1/2.jpg?next=3",
        ] {
            assert_eq!(clean_poster_url(invalid), None);
        }
        assert_eq!(read_poster(&b"x"[..])?, b"x");
        assert_eq!(read_poster(&[][..]), Err(CoreError::Remote));
        assert_eq!(read_poster(std::io::repeat(0)), Err(CoreError::Remote));
        Ok(())
    }

    #[test]
    fn parses_schedule_with_numbered_episodes_and_plain_text(
    ) -> std::result::Result<(), Box<dyn std::error::Error>> {
        let show: Show = serde_json::from_str(
            r#"{
          "id":82, "name":"Example", "premiered":"2010-02-14",
          "summary":"<p><b>One</b> &amp; two.</p><p>Three</p>",
          "image":{"medium":"https://static.tvmaze.com/uploads/images/medium_portrait/1/2.jpg"},
          "_embedded":{"episodes":[
            {"season":1,"number":1,"name":"Pilot","airdate":"2026-11-21","summary":"<p>Intro.</p>"},
            {"season":1,"number":2,"name":null,"airdate":null,"summary":null},
            {"season":1,"number":null,"name":"Unnumbered special","airdate":null},
            {"season":1,"number":2,"name":"Duplicate","airdate":"2026-11-22"}
          ]}
        }"#,
        )?;
        let catalog = show.into_catalog(ID_NAMESPACE + 82)?;
        assert!(catalog.series.overview.contains("One & two."));
        assert!(catalog.series.overview.contains("Three"));
        assert!(!catalog.series.overview.contains("<p>"));
        assert_eq!(
            catalog.series.poster_url.as_deref(),
            Some("https://static.tvmaze.com/uploads/images/medium_portrait/1/2.jpg")
        );
        assert_eq!(catalog.episodes.len(), 2);
        assert_eq!(catalog.episodes[0].air_date.as_deref(), Some("2026-11-21"));
        assert_eq!(catalog.episodes[0].overview, "Intro.");
        assert_eq!(catalog.episodes[1].air_date, None);
        Ok(())
    }
}
