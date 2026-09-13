#!/bin/bash

TOKEN=${1:-alice-token}
N=${2:-100}

CREATE_URL="https://paytm-wallet-transfer-production-4a49.up.railway.app/wallets"

GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m'

echo "Firing $N simultaneous CREATE requests..."
echo "Token: $TOKEN"
echo "============================================================"

for ((i=1; i<=N; i++)); do
    (
        RESPONSE=$(curl -s \
            -w "\n__HTTP_STATUS__:%{http_code}\n__TIME__:%{time_total}s" \
            -X POST \
            -H "Authorization: Bearer $TOKEN" \
            "$CREATE_URL")

        BODY=$(echo "$RESPONSE" | sed '/^__HTTP_STATUS__:/d' | sed '/^__TIME__:/d')
        STATUS=$(echo "$RESPONSE" | grep "__HTTP_STATUS__:" | cut -d: -f2)
        TIME=$(echo "$RESPONSE" | grep "__TIME__:" | cut -d: -f2)

        if [[ "$STATUS" =~ ^2 ]]; then
            WALLET_ID=$(echo "$BODY" | grep -o '"id":[^,}]*' | head -1)
            UPI_ID=$(echo "$BODY" | grep -o '"upi_id":"[^"]*"' | head -1)
            NAME=$(echo "$BODY" | grep -o '"name":"[^"]*"' | head -1)

            echo -e "${GREEN}"
            echo "------------------------------------------------------------"
            echo "Request       : $i"
            echo "HTTP Status   : $STATUS"
            echo "Time          : $TIME"
            echo "Wallet ID     : ${WALLET_ID:-N/A}"
            echo "UPI ID        : ${UPI_ID:-N/A}"
            echo "Name          : ${NAME:-N/A}"
            echo "Response      : $BODY"
            echo "------------------------------------------------------------"
            echo -e "${NC}"
        else
            echo -e "${RED}"
            echo "------------------------------------------------------------"
            echo "Request       : $i"
            echo "HTTP Status   : $STATUS"
            echo "Time          : $TIME"
            echo "ERROR         : $BODY"
            echo "------------------------------------------------------------"
            echo -e "${NC}"
        fi
    ) &
done

wait

echo ""
echo "============================================================"
echo "Burst completed."
