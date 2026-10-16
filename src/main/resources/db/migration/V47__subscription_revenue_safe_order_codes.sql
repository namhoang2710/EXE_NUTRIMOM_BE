-- Admin serializes order codes as strings, but existing user payment APIs expose Long.
-- Reserve a distinct sequence range that is also exact in JavaScript numbers.
ALTER SEQUENCE app.subscription_revenue_order_code
    START WITH 8000000000000000 RESTART WITH 8000000000000000 MAXVALUE 9007199254740991;
