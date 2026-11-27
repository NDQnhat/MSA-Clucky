# Clucky Ordering and Payment Policy

## Fulfillment Methods
Clucky supports two online ordering methods:
1. **Delivery (`DELIVERY`)**: Delivered from your selected Clucky branch to your delivery address (within the branch's delivery radius).
2. **Store Pickup (`PICKUP`)**: Order ahead online and pick up directly at your chosen branch with zero shipping fee.

Note: All items in a cart must belong to a single branch. Price and availability (`isSoldOut`) depend on the branch you select.

## Payment Methods
1. **Cash on Delivery / Pay at Store (`COD`)**:
   - For `DELIVERY` orders: Pay cash upon delivery.
   - For `PICKUP` orders: Pay directly at the branch counter when collecting your order.
   - `COD` orders move immediately to `CONFIRMED` so the kitchen can prepare them.
2. **Bank Transfer via VietQR (`BANK_TRANSFER`)**:
   - After placing the order, Clucky generates a VietQR payment code with a dedicated transfer reference.
   - The order remains in `PENDING_PAYMENT` until the transfer is automatically reconciled via SePay and confirmed (`CONFIRMED`).

## Order Status Flow
- `PENDING_PAYMENT`: Awaiting VietQR bank transfer confirmation.
- `CONFIRMED`: Order confirmed and queued at the branch.
- `PREPARING`: Kitchen is actively preparing your food.
- `READY`: Food is ready (awaiting rider pickup for `DELIVERY`, or ready at counter for `PICKUP`).
- `SHIPPING`: Rider is on the way (`DELIVERY` orders).
- `COMPLETED`: Order delivered/collected and loyalty points settled for signed-in members.
- `CANCELLED` / `DELIVERY_FAILED`: Order cancelled or delivery failed.
