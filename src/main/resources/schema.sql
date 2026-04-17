DROP DATABASE IF EXISTS khanhs_tea_db;
CREATE DATABASE khanhs_tea_db;
USE khanhs_tea_db;

-- PRODUCTS TABLE (from CSV menu)
CREATE TABLE products (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    item_id VARCHAR(20) UNIQUE NOT NULL,
    category VARCHAR(50) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    price_m INT NOT NULL,
    price_l INT NOT NULL,
    available BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_category (category),
    INDEX idx_item_id (item_id)
);

-- ORDERS TABLE
CREATE TABLE orders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id VARCHAR(50) UNIQUE NOT NULL,
    telegram_id BIGINT NOT NULL,
    customer_name VARCHAR(100),
    customer_phone VARCHAR(20),
    total_amount INT NOT NULL DEFAULT 0,
    status VARCHAR(20) DEFAULT 'PENDING',
    payment_status VARCHAR(20) DEFAULT 'UNPAID',
    payment_method VARCHAR(50),
    qr_code_url VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_order_id (order_id),
    INDEX idx_telegram_id (telegram_id),
    INDEX idx_status (status)
);

-- ORDER_ITEMS TABLE
CREATE TABLE order_items (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    size VARCHAR(10) NOT NULL,
    quantity INT NOT NULL DEFAULT 1,
    topping VARCHAR(500),
    unit_price INT NOT NULL,
    total_price INT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
    FOREIGN KEY (product_id) REFERENCES products(id),
    INDEX idx_order_id (order_id)
);

-- INSERT SAMPLE DATA FROM CSV
INSERT INTO products (item_id, category, name, description, price_m, price_l, available) VALUES
('TS01', 'Trà Sữa', 'Trà Sữa Trân Châu Đen', 'Trà sữa thơm béo với trân châu đen dai ngon', 35000, 45000, TRUE),
('TS02', 'Trà Sữa', 'Trà Sữa Trân Châu Trắng', 'Trà sữa mịn với trân châu trắng thơm', 35000, 45000, TRUE),
('TS03', 'Trà Sữa', 'Trà Sữa Truyền Thống', 'Trà sữa nguyên chất không topping', 30000, 40000, TRUE),
('TS04', 'Trà Sữa', 'Trà Sữa Khoai Môn', 'Trà sữa vị khoai môn hấp dẫn', 38000, 48000, TRUE),
('TS05', 'Trà Sữa', 'Trà Sữa Bạc Hà', 'Trà sữa thơm mát bạc hà tươi', 35000, 45000, TRUE),
('TTG01', 'Trà Trái Cây', 'Trà Dâu Tây', 'Trà tươi với vị dâu tây ngọt ngào', 32000, 42000, TRUE),
('TTG02', 'Trà Trái Cây', 'Trà Mâm Xôi', 'Trà mâm xôi tươi mát giải khát', 32000, 42000, TRUE),
('TTG03', 'Trà Trái Cây', 'Trà Chanh Leo', 'Trà chanh leo chua nhẹ sảng khoái', 30000, 40000, TRUE),
('TTG04', 'Trà Trái Cây', 'Trà Vải Thiều', 'Trà vải thiều ngọt hương lạ', 33000, 43000, TRUE),
('TTG05', 'Trà Trái Cây', 'Trà Xoài', 'Trà xoài tươi thơm vị nhiệt đới', 32000, 42000, TRUE),
('CF01', 'Cà Phê', 'Cà Phê Đen', 'Cà phê đen đậm đà truyền thống', 25000, 30000, TRUE),
('CF02', 'Cà Phê', 'Cà Phê Sữa', 'Cà phê sữa béo ngậy hạnh phúc', 28000, 33000, TRUE),
('CF03', 'Cà Phê', 'Cà Phê Caramel', 'Cà phê với ít caramel mượt mà', 30000, 35000, TRUE),
('CF04', 'Cà Phê', 'Cà Phê Mocha', 'Cà phê với chocolate ngọt dịu', 32000, 37000, TRUE),
('CF05', 'Cà Phê', 'Cà Phê Macchiato', 'Cà phê espresso với ít sữa', 27000, 32000, TRUE),
('DX01', 'Đá Xay', 'Đá Xay Dâu Tây', 'Đá xay mịn vị dâu tây tươi mát', 35000, 45000, TRUE),
('DX02', 'Đá Xay', 'Đá Xay Dừa', 'Đá xay vị dừa ngọt dễ chịu', 35000, 45000, TRUE),
('DX03', 'Đá Xay', 'Đá Xay Matcha', 'Đá xay matcha tươi giải khát', 38000, 48000, TRUE),
('DX04', 'Đá Xay', 'Đá Xay Sôcôla', 'Đá xay sôcôla ngọt thơm', 36000, 46000, TRUE),
('TOP01', 'Topping', 'Trân Châu Đen', 'Trân châu đen dai ngon', 5000, 5000, TRUE),
('TOP02', 'Topping', 'Trân Châu Trắng', 'Trân châu trắng mềm dẻo', 5000, 5000, TRUE),
('TOP03', 'Topping', 'Thạch Cà Chua', 'Thạch cà chua mọng nước', 4000, 4000, TRUE),
('TOP04', 'Topping', 'Thạch Xanh', 'Thạch xanh mát lạnh tươi', 4000, 4000, TRUE),
('TOP05', 'Topping', 'Nước Cốt Dừa', 'Nước cốt dừa béo ngậy', 6000, 6000, TRUE),
('TOP06', 'Topping', 'Kem Tươi', 'Kem tươi mịn ngọt dịu', 8000, 8000, TRUE),
('TOP07', 'Topping', 'Gelée Khoai Môn', 'Gelée khoai môn vị độc đáo', 5000, 5000, TRUE),
('TOP08', 'Topping', 'Bột Trà Xanh', 'Bột trà xanh thơm ngậy', 3000, 3000, TRUE);