#!/bin/sh
# Creates the EDT topics, with every setting written out (the broker does not create topics on its own:
# KAFKA_AUTO_CREATE_TOPICS_ENABLE=false). Runs once at every "docker compose up"; --if-not-exists makes that safe.
#
# Topic names and settings are PLACEHOLDERS: DCC owns the Kafka cluster and its topics. [CONFIRM]
#
#   partitions          3   messages with the same key (a device) always go to the same partition, so a consumer
#                           sees the events of one device in order. More partitions = more consumers in parallel.
#   replication-factor  1   one broker locally. In production 3: each partition is copied to 3 brokers.
#   retention.ms        7 days, then old messages are deleted (cleanup.policy=delete). Consumers must read before.
#   min.insync.replicas 1   with acks=all, how many copies must have a message before the producer gets an OK
#                           (production: 2 of 3).
set -e
BOOTSTRAP=kafka:9092
TOPICS=/opt/kafka/bin/kafka-topics.sh

for topic in edt.flow-steps edt.device-results edt.batch-results; do
  $TOPICS --bootstrap-server $BOOTSTRAP --create --if-not-exists --topic "$topic" \
    --partitions 3 --replication-factor 1 \
    --config retention.ms=604800000 \
    --config cleanup.policy=delete \
    --config min.insync.replicas=1
done

$TOPICS --bootstrap-server $BOOTSTRAP --describe --topic 'edt\..*'
