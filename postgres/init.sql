CREATE TABLE customers (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(200) NOT NULL,
    email        VARCHAR(200) NOT NULL UNIQUE,
    city         VARCHAR(100) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE products (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(200)   NOT NULL,
    category     VARCHAR(100)   NOT NULL,
    price        NUMERIC(12, 2) NOT NULL,
    stock        INTEGER        NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

CREATE TABLE orders (
    id           BIGSERIAL PRIMARY KEY,
    customer_id  BIGINT         NOT NULL REFERENCES customers(id),
    status       VARCHAR(20)    NOT NULL,
    total_amount NUMERIC(14, 2) NOT NULL,
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

CREATE TABLE order_items (
    id          BIGSERIAL PRIMARY KEY,
    order_id    BIGINT         NOT NULL REFERENCES orders(id),
    product_id  BIGINT         NOT NULL REFERENCES products(id),
    quantity    INTEGER        NOT NULL,
    unit_price  NUMERIC(12, 2) NOT NULL
);

ALTER TABLE customers   REPLICA IDENTITY FULL;
ALTER TABLE products    REPLICA IDENTITY FULL;
ALTER TABLE orders      REPLICA IDENTITY FULL;
ALTER TABLE order_items REPLICA IDENTITY FULL;

CREATE PUBLICATION dbz_publication FOR TABLE customers, products, orders, order_items;

INSERT INTO products (name, category, price, stock) VALUES
    ('Kopi Arabica Gayo 250g',  'Beverages',   85000, 200),
    ('Teh Hijau Premium 100g',  'Beverages',   45000, 150),
    ('Keyboard Mechanical RGB', 'Electronics', 1250000, 30),
    ('Mouse Wireless Ergonomis','Electronics', 350000, 80),
    ('Notebook A5 Hardcover',   'Stationery',  55000, 500),
    ('Pulpen Gel 0.5mm',        'Stationery',  12000, 1000),
    ('Tas Ransel Kanvas',       'Fashion',     275000, 60),
    ('Sepatu Sneakers Putih',   'Fashion',     650000, 40);
