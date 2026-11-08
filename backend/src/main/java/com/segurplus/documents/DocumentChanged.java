package com.segurplus.documents;

import java.util.UUID;

/**
 * Evento de aplicación: un documento nació o cambió y su copia derivada en Dify debe (re)crearse o actualizarse.
 * Solo lleva el ID: el indexador lee el estado vigente de la BD (fuente de verdad), nunca del evento.
 */
public record DocumentChanged(UUID documentId, Kind kind) {

    public enum Kind { CREATED, METADATA_CHANGED, DELETED, REINDEX }
}
