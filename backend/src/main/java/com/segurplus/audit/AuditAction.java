package com.segurplus.audit;

/** Acciones permitidas por el CHECK de {@code audit_logs.action}. */
public enum AuditAction {
    LOGIN, LOGIN_FAILED, DOCUMENT_READ, DOCUMENT_DOWNLOAD, DOCUMENT_UPLOAD, ACCESS_DENIED, CHAT, TOOL_CALL,
    FRAGMENT_DROPPED, IGNORED_CLIENT_FIELD, TOKEN_REFRESH, TOKEN_REUSE_DETECTED, LOGOUT
}
