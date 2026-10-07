CREATE TABLE categorias (
    id           UUID PRIMARY KEY,
    nombre       VARCHAR(100) NOT NULL,
    descripcion  VARCHAR(500),
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_categorias_nombre UNIQUE (nombre)
);
