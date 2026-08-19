package com.inninglog.domain.oauth.google;

public interface GoogleIdentityTokenVerifier {

    GoogleUserInfo verify(String credential);
}
