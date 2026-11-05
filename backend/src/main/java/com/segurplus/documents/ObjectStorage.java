package com.segurplus.documents;

import java.io.InputStream;

/** Almacén de originales. El acceso es siempre vía backend: no se generan URLs públicas ni prefirmadas. */
interface ObjectStorage {

    void put(String key, byte[] content, String contentType);

    InputStream get(String key);

    void delete(String key);
}
