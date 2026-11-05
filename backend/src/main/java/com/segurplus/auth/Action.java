package com.segurplus.auth;

public enum Action {
    READ, DOWNLOAD, CREATE, UPDATE, DELETE, ADMINISTER;

    /** READ y DOWNLOAD van unidos (decisión del propietario); el resto son escrituras reservadas a MANAGER. */
    public boolean isRead() {
        return this == READ || this == DOWNLOAD;
    }
}
