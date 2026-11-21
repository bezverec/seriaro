use std::time::Duration;

use reqwest::{blocking::Client as HttpClient, redirect::Policy, StatusCode};

use crate::{CoreError, Result};

pub(crate) fn http_client() -> Result<HttpClient> {
    HttpClient::builder()
        .use_rustls_tls()
        .https_only(true)
        .redirect(Policy::none())
        .connect_timeout(Duration::from_secs(10))
        .timeout(Duration::from_secs(30))
        .build()
        .map_err(|_| CoreError::Network)
}

pub(crate) fn check_status(status: StatusCode) -> Result<()> {
    if status.is_success() {
        Ok(())
    } else if matches!(status, StatusCode::UNAUTHORIZED | StatusCode::FORBIDDEN) {
        Err(CoreError::Unauthorized)
    } else if status.is_server_error() || status == StatusCode::TOO_MANY_REQUESTS {
        Err(CoreError::Network)
    } else {
        Err(CoreError::Remote)
    }
}
