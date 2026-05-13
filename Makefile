COMPOSE ?= podman compose

.PHONY: up down register status logs clean ch psql topics generator

up:
	$(COMPOSE) up -d

down:
	$(COMPOSE) down

clean:
	$(COMPOSE) down -v

register:
	curl -fsS -X POST -H "Content-Type: application/json" \
		--data @debezium/postgres-connector.json \
		http://localhost:8083/connectors | jq .

status:
	curl -fsS http://localhost:8083/connectors/shop-postgres-source/status | jq .

logs:
	$(COMPOSE) logs -f --tail=100

psql:
	psql postgresql://shop:shop@localhost:5433/shop

ch:
	podman exec -it olap-clickhouse clickhouse-client --user analytics --password analytics --database shop_analytics

topics:
	podman exec -it oltp-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list

generator:
	cd data-generator && ./mvnw spring-boot:run
