-- F4: resultado de la verificación posterior (citas y cifras) guardado en el mensaje (04-chat-flow). Solo metadatos y cifras
-- del propio mensaje; sin contenido de documentos.
ALTER TABLE messages ADD COLUMN verification jsonb;
ALTER TABLE messages ADD CONSTRAINT ck_messages_verification_object CHECK (verification IS NULL OR jsonb_typeof(verification) = 'object');
