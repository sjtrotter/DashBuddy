# Offline county-subdivision cells — vintage 2023

Public-domain US Census data; the shipped CSV is pinned to vintage 2023.
County-subdivision internal points (latitude/longitude rounded to 3 decimal places):
https://www2.census.gov/geo/docs/maps-data/data/gazetteer/2023_Gazetteer/2023_Gaz_cousubs_national.zip
July 2023 CBSA delineation:
https://www2.census.gov/programs-surveys/metro-micro/geographies/reference-files/2023/delineation-files/list1_2023.xlsx
Join the first five digits of each 10-digit subdivision GEOID (county FIPS) to
delineation state FIPS + county FIPS (5 digits).
Matched counties take CBSA code and metro/micro kind; unmatched counties have empty CBSA and kind `none`.
Unmatched counties derive a STATE cell.
`domain/src/main/resources/geo/cousub_cells_2023.csv.gz` uses gzip compression and LF line endings,
with header `geoid,state,lat,lon,cbsa,kind`: 36,434 subdivision rows covering 3,222 counties and 52 states.
Nearest internal point is an approximation, not a county polygon lookup.
regenerate = a new vintage = a new `RegionCell.vintage`.
