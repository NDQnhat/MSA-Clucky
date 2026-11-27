# Clucky Delivery and Branch Policy

## Delivery Fee and Service Radius
- **Store Pickup (`PICKUP`)**: Delivery fee is always **0 VND**.
- **Delivery (`DELIVERY`)**:
  - A **chain-wide standard delivery fee** applies (default **15,000 VND**, configurable by Clucky Admin).
  - Your delivery coordinates are validated against the selected branch's coordinates and **delivery radius (`deliveryRadiusKm`)**. If an address falls outside the branch's radius, please choose a closer branch or switch to `PICKUP`.

## Branches and Opening Hours
- Each Clucky branch has its own operating hours (`openTime` – `closeTime`) and operational status (`OPEN`, `CLOSED_TEMP`, `INACTIVE`).
- Only branches in **`OPEN`** status during active operating hours accept online orders.
- Branch-specific pricing (`storePrice`) and item availability (`isSoldOut`) may vary by store.
