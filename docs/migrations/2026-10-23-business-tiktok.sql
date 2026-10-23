-- TikTok como red social del local (iconos en la carta): misma forma que
-- instagram_url. ddl-auto:update crea la columna en bases gestionadas por
-- entidades; la migración deja el cambio explícito para bases a mano.
ALTER TABLE businesses ADD COLUMN IF NOT EXISTS tiktok_url VARCHAR(500);
