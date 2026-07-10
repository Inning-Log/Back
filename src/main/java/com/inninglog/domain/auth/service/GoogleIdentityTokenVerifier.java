package com.inninglog.domain.auth.service;

public interface GoogleIdentityTokenVerifier {

    GoogleUserInfo verify(String credential);
}
