CREATE TABLE payment_transaction (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES auth_user(id),
    order_id VARCHAR(100) NOT NULL UNIQUE,
    tier VARCHAR(20) NOT NULL,
    amount BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    vnp_txn_ref VARCHAR(100),
    vnp_transaction_no VARCHAR(100),
    vnp_bank_code VARCHAR(50),
    vnp_response_code VARCHAR(20),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE INDEX idx_payment_tx_user_id ON payment_transaction(user_id);
CREATE INDEX idx_payment_tx_order_id ON payment_transaction(order_id);
