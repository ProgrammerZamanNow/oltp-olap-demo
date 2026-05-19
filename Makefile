COMPOSE ?= podman compose

.PHONY: up down rebuild register status restart-connector logs clean ch psql topics generator-logs generator-host

up:
	$(COMPOSE) up -d

down:
	$(COMPOSE) down

rebuild:
	$(COMPOSE) up -d --build generator

clean:
	$(COMPOSE) down -v

register:
	curl -fsS -X POST -H "Content-Type: application/json" \
		--data @debezium/postgres-connector.json \
		http://localhost:8083/connectors | jq .

status:
	@curl -fsS http://localhost:8083/connectors/shop-postgres-source/status | jq '{connector: .connector.state, tasks: [.tasks[] | {id, state}]}'

restart-connector:
	@curl -fsS -X POST http://localhost:8083/connectors/shop-postgres-source/tasks/0/restart && echo "task restarted"

logs:
	$(COMPOSE) logs -f --tail=100

generator-logs:
	$(COMPOSE) logs -f --tail=100 generator

psql:
	psql postgresql://shop:shop@localhost:15432/shop

ch:
	podman exec -it olap-clickhouse clickhouse-client --user analytics --password analytics --database shop_analytics

topics:
	podman exec -it oltp-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list

generator-host:
	cd data-generator && mvn spring-boot:run
