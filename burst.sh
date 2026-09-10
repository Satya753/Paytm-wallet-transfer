#!/bin/bash

N=${1:-100}

GET_URL="http://localhost:8080/api/wallet/123"
CREATE_URL="http://localhost:8080/api/wallet"

echo "Firing $N simultaneous GET requests..."
for ((i=1; i<=N; i++)); do
    curl -s -o /dev/null -w "GET $i: %{http_code} %{time_total}s\n" \
        "$GET_URL" &
done

wait

echo ""
echo "Firing $N simultaneous CREATE requests..."
for ((i=1; i<=N; i++)); do
    curl -s -o /dev/null -w "POST $i: %{http_code} %{time_total}s\n" \
        -X POST \
        -H "Content-Type: application/json" \
        -d "{\"userId\":\"user-$i\",\"amount\":100}" \
        "$CREATE_URL" &
done

wait

echo ""
echo "Burst completed."
