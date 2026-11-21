use std::io::Read;

use reqwest::Url;

use crate::http::{check_status, http_client};
use crate::store::MAX_BACKUP_BYTES;
use crate::{CoreError, Result};

fn backup_url(value: &str) -> Result<Url> {
    let url = Url::parse(value.trim()).map_err(|_| CoreError::InvalidWebDavUrl)?;
    if url.scheme() != "https"
        || url.host_str().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
        || url.path().ends_with('/')
        || url.fragment().is_some()
        || url.query().is_some()
    {
        return Err(CoreError::InvalidWebDavUrl);
    }
    Ok(url)
}

pub(crate) fn upload(url: &str, username: &str, password: &str, data: Vec<u8>) -> Result<()> {
    let response = http_client()?
        .put(backup_url(url)?)
        .basic_auth(username, Some(password))
        .header(reqwest::header::CONTENT_TYPE, "application/json")
        .body(data)
        .send()
        .map_err(|_| CoreError::Network)?;
    check_status(response.status())
}

pub(crate) fn download(url: &str, username: &str, password: &str) -> Result<Vec<u8>> {
    let response = http_client()?
        .get(backup_url(url)?)
        .basic_auth(username, Some(password))
        .send()
        .map_err(|_| CoreError::Network)?;
    check_status(response.status())?;
    read_limited(response, MAX_BACKUP_BYTES)
}

fn read_limited(body: impl Read, maximum: usize) -> Result<Vec<u8>> {
    let mut data = Vec::new();
    body.take(maximum as u64 + 1)
        .read_to_end(&mut data)
        .map_err(|_| CoreError::Network)?;
    if data.len() > maximum {
        return Err(CoreError::FileTooLarge);
    }
    Ok(data)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_explicit_https_file_and_rejects_unsafe_forms() {
        assert!(backup_url(" https://cloud.example/dav/series.json ").is_ok());
        for url in [
            "http://cloud.example/backup.json",
            "https://user:secret@cloud.example/backup.json",
            "https://cloud.example/folder/",
            "https://cloud.example/file.json?token=secret",
            "https://cloud.example/file.json#fragment",
        ] {
            assert_eq!(backup_url(url), Err(CoreError::InvalidWebDavUrl));
        }
    }

    #[test]
    fn streams_with_an_explicit_size_limit() {
        assert_eq!(read_limited(&b"123"[..], 3), Ok(b"123".to_vec()));
        assert_eq!(read_limited(&b"1234"[..], 3), Err(CoreError::FileTooLarge));
    }
}
