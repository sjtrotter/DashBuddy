# Offline county cells — vintage 2023

Public-domain US Census data; shipped CSVs are pinned to vintage 2023.
County internal points (latitude/longitude rounded to 4 decimal places):
https://www2.census.gov/geo/docs/maps-data/data/gazetteer/2023_Gazetteer/2023_Gaz_counties_national.zip
July 2023 CBSA delineation and display titles:
https://www2.census.gov/programs-surveys/metro-micro/geographies/reference-files/2023/delineation-files/list1_2023.xlsx
Join Gazetteer county GEOID to delineation state FIPS + county FIPS (5 digits).
Matched counties take CBSA code and metro/micro kind; unmatched counties have empty CBSA and kind `none`.
Unmatched counties derive a STATE cell; titles are display reference only.
The titles CSV has 935 CBSA entries plus two Census source-note rows (ignored by the title loader).
Nearest internal point is an approximation, not a county polygon lookup.
regenerate = a new vintage = a new `RegionCell.vintage`.
