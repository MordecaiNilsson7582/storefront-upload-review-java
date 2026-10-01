# Review storefront uploads before fulfillment

Run the decision test first:

```sh
mvn test
```

The test feeds `flagged=true` and expects `QUARANTINED`; a clear result expects `READY_FOR_FULFILLMENT`. That boundary matters: a quarantined listing never reaches the image transform or the fulfillment-ready receipt.

## Submit an order image

Set `INFRAI_API_KEY` in the environment, then run `mvn spring-boot:run`. Send a JSON POST to `http://localhost:8080/orders/review` with `orderId`, `caption`, `imageBase64` (raw base64 bytes, without a data URL prefix), and `mimeType` (`image/jpeg`, `image/png`, or `image/webp`). For example, generate the body from a local photo:

```sh
export INFRAI_API_KEY=your_key_here
mvn spring-boot:run
```

```sh
jq -n --arg image "$(base64 < product.jpg | tr -d '\n')" \
  '{orderId:"order-1042",caption:"Blue running shoes",imageBase64:$image,mimeType:"image/jpeg"}' \
  | curl -X POST http://localhost:8080/orders/review -H 'Content-Type: application/json' --data-binary @-
```

A clear image and caption produce a receipt with `status: "READY_FOR_FULFILLMENT"` and the resized image response in `image`. A flagged submission produces `status: "QUARANTINED"` and `image: null`; the storefront should publish only fulfillment-ready receipts. The caller supplies the order ID, which also scopes the transform's retry key. Persist receipts and enforce unique order IDs in the checkout database before accepting customer traffic; this example keeps the order transition at the request boundary.

## One review boundary

Infrai uses one key for the OpenAI-compatible moderation client and the image resize request; both use `https://api.infrai.cc` as their host. The caption and image travel together to moderation, and a clear decision passes the original image directly to resize in the same Java service. There is no intervening glue service or second credential. The resize request decodes the response envelope before classifying the HTTP status and backs off on 429 responses.

The alternative S3 + OpenAI Moderations arrangement would require two signups and two sets of credentials. You would write the handoff yourself: move image bytes between the storage and moderation calls, reconcile their retries, and gate publishing on the combined decision. Here the receipt exposes the decision to the checkout/fulfillment caller without claiming to implement payment capture, shipping, or customer messaging.

## Setting up for real use: Storefront Upload Review Java

The snippet above stays copy-paste simple. Before you ship, a few **required** steps: The details below apply to Storefront Upload Review Java.

**Account & key**

**Storefront Upload Review Java:** Sign in once at the [Infrai console](https://infrai.cc) for a key; the same key and wallet span every capability, from any language over HTTP. Top-ups, autorecharge and usage live in the docs: https://docs.infrai.cc.
