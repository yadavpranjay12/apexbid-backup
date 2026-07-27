-- 1. SEED USERS
INSERT INTO users (id, username, email, password_hash, created_at)
VALUES (1, 'alice_seller', 'alice@apexbid.com', '$2a$10$NXJ6wbyWQLK1Y6mSfZ9KueEupbLg7C.jM/N3XQzW2eF8gZ2o2N3Ki',
        '2026-05-18T12:00:00Z'),
       (2, 'bob_merchant', 'bob@apexbid.com', '$2a$10$NXJ6wbyWQLK1Y6mSfZ9KueEupbLg7C.jM/N3XQzW2eF8gZ2o2N3Ki',
        '2026-05-18T12:00:00Z'),
       (3, 'charlie_bidder', 'charlie@apexbid.com', '$2a$10$NXJ6wbyWQLK1Y6mSfZ9KueEupbLg7C.jM/N3XQzW2eF8gZ2o2N3Ki',
        '2026-05-18T12:00:00Z'),
       (4, 'david_whale', 'david@apexbid.com', '$2a$10$NXJ6wbyWQLK1Y6mSfZ9KueEupbLg7C.jM/N3XQzW2eF8gZ2o2N3Ki',
        '2026-05-18T12:00:00Z')
ON CONFLICT (id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('users', 'id'), COALESCE(MAX(id), 1))
FROM users;
INSERT INTO administrators (user_id) VALUES (4) ON CONFLICT (user_id) DO NOTHING;

-- 2. SEED WALLETS (Values in absolute cents)
INSERT INTO wallets (user_id, balance, version)
VALUES (1, 5000000, 0),  -- $50,000.00
       (2, 7500000, 0),  -- $75,000.00
       (3, 15000000, 0), -- $150,000.00
       (4, 50000000, 0)  -- $500,000.00
ON CONFLICT (user_id) DO NOTHING;


-- 3. SEED PRODUCTS
INSERT INTO products (id, name, description, image_url, secret_code, seller_id, buyer_id)
VALUES (201, 'RTX 5090 Midnight Drop', 'Brand new next-gen graphics architecture.',
        'https://assets.apexbid.com/5090.png', 'SECRET_5090', 1, NULL),
       (202, 'MacBook Pro M5 Max Overkill', '64GB Unified Memory workspace workhorse.',
        'https://assets.apexbid.com/macbook.png', 'SECRET_M5_MAC', 1, NULL),
       (203, 'Travis Scott Fragment AJ1', 'Deadstock sneaker collectable size 10.5.',
        'https://assets.apexbid.com/aj1.png', 'SECRET_AJ1', 2, NULL),
       (204, 'Rolex Submariner Gold Edition', 'Classic 18k yellow gold luxury timepiece.',
        'https://assets.apexbid.com/rolex.png', 'SECRET_ROLEX', 2, NULL),
       (205, 'Sony PS6 Developer Kit', 'Early access prototype hardware unit.', 'https://assets.apexbid.com/ps6.png',
        'SECRET_PS6', 1, NULL),
       (206, '1-Minute Lightning Test Item', 'Item calibrated for rapid testing execution.',
        'https://assets.apexbid.com/test.png', 'SECRET_LIGHTNING', 2, NULL)
ON CONFLICT (id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('products', 'id'), COALESCE(MAX(id), 1))
FROM products;


-- 4. SEED AUCTIONS
INSERT INTO auctions (id, title, auction_type, status, start_price, winning_bid, product_id, seller_id, winner_id,
                      start_time, end_time)
VALUES (1001, 'RTX 5090 Midnight Drop', 'ANTI_SNIPE', 'ACTIVE', 150000, NULL, 201, 1, NULL, '2026-07-27T15:00:00Z',
        '2026-07-28T03:00:00Z'),
       (1002, 'Travis Scott Fragment AJ1', 'STANDARD', 'ACTIVE', 120000, NULL, 203, 2, NULL, '2026-07-27T17:00:00Z',
        '2026-07-28T19:45:00Z'),
       (1003, 'MacBook Pro M5 Max Overkill', 'STANDARD', 'UPCOMING', 350000, NULL, 202, 1, NULL, '2026-07-27T20:15:00Z',
        '2026-07-28T01:15:00Z'),
       (1004, 'Rolex Submariner Gold Edition', 'ANTI_SNIPE', 'UPCOMING', 950000, NULL, 204, 2, NULL,
        '2026-07-27T20:23:00Z', '2026-07-28T02:23:00Z'),
       (1005, 'Sony PS6 Developer Kit', 'ANTI_SNIPE', 'UPCOMING', 500000, NULL, 205, 1, NULL, '2026-07-28T12:00:00Z',
        '2026-07-29T12:00:00Z'),
       (1006, 'Lightning Test Auction', 'STANDARD', 'UPCOMING', 50000, NULL, 206, 2, NULL, '2026-07-27T19:45:00Z',
        '2026-07-27T19:46:00Z')
ON CONFLICT (id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('auctions', 'id'), COALESCE(MAX(id), 1))
FROM auctions;


-- 5. SEED INITIAL TRANSACTIONS
INSERT INTO transactions (id, sender_id, receiver_id, auction_id, amount, transaction_type, created_at)
VALUES (1, NULL, 3, NULL, 15000000, 'DEPOSIT', '2026-05-18T12:00:00Z'),
       (2, NULL, 4, NULL, 50000000, 'DEPOSIT', '2026-05-18T12:00:00Z')
ON CONFLICT (id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('transactions', 'id'), COALESCE(MAX(id), 1))
FROM transactions;