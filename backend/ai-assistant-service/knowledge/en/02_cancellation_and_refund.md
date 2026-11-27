# Clucky Cancellation and Refund Policy

## Order Cancellation Rules
- Customers can cancel an order while it is in **`PENDING_PAYMENT`** or **`CONFIRMED`** status (before kitchen preparation starts), providing a cancellation reason.
- **Cancellation is not allowed** once the order enters **`PREPARING`**, **`READY`**, **`SHIPPING`**, or **`COMPLETED`**.
- When an eligible order is cancelled, any reserved loyalty points are released back to your loyalty balance.

## Refund Eligibility and Process (`BANK_TRANSFER`)
Customers may submit a refund request for a paid `BANK_TRANSFER` (`PAID`) order in the following eligible cases:
1. The order was validly cancelled from `PENDING_PAYMENT` or `CONFIRMED` before kitchen preparation (`PREPARING`).
2. A `DELIVERY` order ended in `DELIVERY_FAILED` as recorded by the Shipping service.

To submit a refund request, customers must provide accurate recipient bank details:
- 6-digit bank BIN (`bankBin`) and bank name (`bankName`)
- Bank account number (`bankAccountNo`)
- Account holder name (`bankAccountName`)
- Refund reason (`reason`)

`COD` orders that were not paid do not require a bank transfer refund.
