CREATE TABLE IF NOT EXISTS file (
    id BIGSERIAL PRIMARY KEY,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(100),
    file_key VARCHAR(100),
    storage_type VARCHAR(10),
    size BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(10),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Insert test data
INSERT INTO file (file_name, content_type, file_key, storage_type, size, status)
VALUES
    ('Test_Data_1MB_PDF.pdf', 'application/pdf', '1', 'HTTP', 1052352, 'CLEAN'),
    ('Test_Data_500KB_PDF.pdf', 'application/pdf', '2', 'HTTP', 512088, 'CLEAN')
ON CONFLICT DO NOTHING;