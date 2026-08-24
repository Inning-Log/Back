package com.inninglog.domain.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "push_registration_locks")
public class PushRegistrationLock {

    public static final short GLOBAL_LOCK_ID = 1;

    @Id
    private Short id;

    @Column(nullable = false, length = 50)
    private String name;

    protected PushRegistrationLock() {
    }
}
