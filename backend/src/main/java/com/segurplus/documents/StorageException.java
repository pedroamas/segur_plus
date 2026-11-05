package com.segurplus.documents;

/** Fallo del almacén; se traduce a 500 genérico (sin detalles del almacén en la respuesta). */
class StorageException extends RuntimeException {

    StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
