-- Demo-only plaintext passwords, duplicated from k8s/base/postgres/secret.yaml and
-- docker-compose.yml since Postgres init scripts run as plain SQL with no secret indirection.
CREATE USER user_service WITH PASSWORD 'user-service-demo-pw';
CREATE DATABASE user_service OWNER user_service;

CREATE USER order_service WITH PASSWORD 'order-service-demo-pw';
CREATE DATABASE order_service OWNER order_service;

CREATE USER admin_service WITH PASSWORD 'admin-service-demo-pw';
CREATE DATABASE admin_service OWNER admin_service;

CREATE USER keycloak WITH PASSWORD 'keycloak-demo-pw';
CREATE DATABASE keycloak OWNER keycloak;
