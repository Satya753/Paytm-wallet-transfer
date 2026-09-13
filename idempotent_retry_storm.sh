#!/bin/bash

K=${1:-100}

TRANSFER_URL="https://paytm-wallet-transfer-production-4a49.up.railway.app/transfers"

FROM="alice@wallet"
TO="bob@wallet"
AMOUNT=500

# Same key for EVERY request
IDEMPOTENCY_KEY="storm-test-$(date +%s)"

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[0;33m'
NC='\033[0m'

echo "============================================================"
echo "Idempotent Retry Storm"
echo "============================================================"
echo "Requests       : $K"
echo "From           : $FROM"
echo "To             : $TO"
echo "Amount         : $AMOUNT paise"
echo "Idempotency Key: $IDEMPOTENCY_KEY"
echo "============================================================"
echo ""

for ((i=1; i<=K; i++)); do
    (
        RESPONSE=$(curl -s \
            -w "\n__HTTP_STATUS__:%{http_code}\n__TIME__:%{time_total}" \
            -X POST \
            "$TRANSFER_URL" \
            -H "Authorization: Bearer alice-token" \
            -H "Content-Type: application/json" \
            -d "{
                \"from\": \"$FROM\",
                \"to\": \"$TO\",
                \"amount_paise\": $AMOUNT,
                \"idempotency_key\": \"$IDEMPOTENCY_KEY\"
            }")

        BODY=$(echo "$RESPONSE" | sed '/^__HTTP_STATUS__:/d' | sed '/^__TIME__:/d')
        STATUS=$(echo "$RESPONSE" | grep "__HTTP_STATUS__:" | cut -d: -f2)
        TIME=$(echo "$RESPONSE" | grep "__TIME__:" | cut -d: -f2)

        if [[ "$STATUS" =~ ^2 ]]; then
            echo -e "${GREEN}SUCCESS${NC} Request=$i Status=$STATUS Time=${TIME}s"
            echo "         Response: $BODY"
        else
            echo -e "${RED}ERROR${NC}   Request=$i Status=$STATUS Time=${TIME}s"
            echo "         Response: $BODY"
        fi
    ) &
done

wait

echo ""
echo "============================================================"
echo "Retry storm completed."
echo "============================================================"
echo "Idempotency key used by all requests:"
echo "$IDEMPOTENCY_KEY"
