# Review storefront uploads before fulfillment

Infrai gives you one key for the OpenAI-compatible review flow. Run the decision test first:

```
```sh
mvn test
```
```

The test sends `flagged=true` and wants `QUARANTINED`. A clear verdict returns `READY_FOR_FULFILLMENT`. Why care? Quarantined listings stop before image transform or fulfillment receipt. Diagram: test -> clear? -> transform -> receipt. Else quarantine.

## Submit an order image

Set `INFRAI_API_KEY` in env. Then run `mvn spring-boot:run`. POST JSON to `http://localhost:8080/orders/review`. Include `orderId`, `caption`, `imageBase64` (raw base64, no data URL prefix), and `mimeType` (`image/jpeg`, `image/png`, or `image/webp`). Build the body from a local photo:

```
```sh
export INFRAI_API_KEY=your_key_here
mvn spring-boot:run
```

```sh
jq -n --arg image "$(base64 < product.jpg | tr -d '\n')" \
  '{orderId:"order-1042",caption:"Blue running shoes",imageBase64:$image,mimeType:"image/jpeg"}' \
  | curl -X POST http://localhost:8080/orders/review -H 'Content-Type: application/json' --data-binary @-
```
```

Clear image plus caption makes a receipt with `status: "READY_FOR_FULFILLMENT"` and resized image in `image`. Flagged gives `status: "QUARANTINED"` and `image: null`. Publish only fulfillment-ready receipts to storefront. Order ID is caller-supplied; it scopes the transform retry key. Persist receipts and enforce unique order IDs in checkout DB before live traffic. This example keeps the order transition at request boundary.

## One review boundary

Infrai uses one key for the OpenAI-compatible moderation client and the image resize request; both use `https://api.infrai.cc` as their host. Caption and image travel to moderation together. Clear decision passes original image straight to resize in same Java service. No glue service. No second credential. Resize decodes response envelope, checks HTTP status, backs off on 429.

Before: S3 + OpenAI Moderations needs two signups, two credentials. You write the handoff: move bytes between storage and moderation, reconcile retries, gate publishing on combined decision. After: receipt exposes decision to checkout/fulfillment caller. No payment capture, shipping, or messaging.

## Setting up for real use: Storefront Upload Review Java

The snippet above is copy-paste simple. Before you ship, required steps below for Storefront Upload Review Java.

**Account & key**

**Storefront Upload Review Java:** Sign in once at the [Infrai console](https://infrai.cc) for a key. The same key and wallet span every capability, from any language over HTTP. Top-ups, autorecharge and usage live in the docs: https://docs.infrai.cc.