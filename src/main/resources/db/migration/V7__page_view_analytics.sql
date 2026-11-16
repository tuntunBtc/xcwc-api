CREATE TABLE analytics_page_view (
  id VARCHAR(32) PRIMARY KEY,
  visit_date DATE NOT NULL,
  session_hash CHAR(64) NOT NULL,
  page_code VARCHAR(20) NOT NULL,
  user_agent VARCHAR(768) NOT NULL,
  viewed_at TIMESTAMP NOT NULL
);
CREATE INDEX analytics_view_date_idx ON analytics_page_view(visit_date);
CREATE INDEX analytics_viewed_at_idx ON analytics_page_view(viewed_at);
