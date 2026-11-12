# Play Console: precise location declaration

The answers gig.log gives in Play Console's precise location declaration, required from 2027-01-27 for every app that
requests `ACCESS_FINE_LOCATION` (Play Console Help, answer 17033915). They describe the Play build
(`cloud.trotter.log.gig`). They are drawn from [PRIVACY.md](../PRIVACY.md) § 2.4, which names the code behind each
claim. When the code or PRIVACY.md changes a claim made here, this file changes in the same PR.

## Declaration answers

### Features that need precise location

**Live tracking.** gig.log counts the miles a driver drives during a shift. It samples GPS for as long as the shift
runs and stops when the shift ends. The store listing promotes this under "SHIFTS AND MILES".

The gas-price lookup also reads a location, but it needs only the U.S. state. It rounds the location to about 11 km
before using it, so it is not a reason for precise location and is not declared.

### Why approximate location or the location button is not enough

> gig.log records a delivery driver's miles while a shift is running, the miles behind each delivery's pay per
> mile, cost and the driver's mileage records. Approximate location is accurate to a few kilometres, which is longer
> than many delivery legs, so it cannot count the miles. The location button gives one location for one action;
> counting miles needs a fix every few seconds for the whole shift. Fixes are used on the phone only: no location is
> uploaded, shared or written to a log, and sampling stops when the shift ends.

## Facts behind the answers

- `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` are declared in `app/src/main/AndroidManifest.xml`; the app
  declares no background location permission.
- The odometer requests high-accuracy fixes every 5 seconds (at least 2 seconds and 5 metres apart,
  `FusedLocationDataSource`), only while a shift is active (`OdometerEffectHandler`). `OdometerFixPolicy` judges each
  fix before it can move the mileage, and a rejected fix is logged as numbers and a reason, never as a coordinate.
- The gas-price lookup is off by default. It gives Android's `Geocoder` a location rounded to one decimal place of a
  degree (`GeocoderInput`), never the exact fix.
