CREATE TABLE materiales (
    id                          UUID PRIMARY KEY,
    nombre                      VARCHAR(140)   NOT NULL,
    categoria_id                UUID           NOT NULL,
    cantidad_disponible         INTEGER        NOT NULL,
    cantidad_total              INTEGER        NOT NULL,
    alto                        NUMERIC(10, 2) NOT NULL,
    ancho                       NUMERIC(10, 2) NOT NULL,
    diametro                    NUMERIC(10, 2),
    unidad_medida               VARCHAR(20)    NOT NULL,
    color                       VARCHAR(50)    NOT NULL,
    material_fisico             VARCHAR(100)   NOT NULL,
    estado                      VARCHAR(20)    NOT NULL,
    ubicacion                   VARCHAR(200)   NOT NULL,
    precio_unitario             NUMERIC(10, 2) NOT NULL,
    proveedor                   VARCHAR(200)   NOT NULL,
    fecha_alta                  TIMESTAMPTZ    NOT NULL,
    fecha_ultima_modificacion   TIMESTAMPTZ    NOT NULL,
    CONSTRAINT fk_materiales_categoria FOREIGN KEY (categoria_id) REFERENCES categorias (id),
    CONSTRAINT chk_materiales_cantidad CHECK (cantidad_disponible >= 0 AND cantidad_disponible <= cantidad_total)
);

CREATE INDEX idx_materiales_categoria_id ON materiales (categoria_id);
