# Third-party notices

gig.log ships the third-party data below. The app's Privacy page links this file ("Open-source notices").

## Common-word list for screen reviews

File: `core/pipeline/src/main/resources/census/review-common-words.txt` (bundled in the app). A screen review shows a
word only if every token is on this list or in the bundled recognition rules (`ReviewAllowList`, ADR-0011 §15).

- Source: orgtre/google-books-ngram-frequency, <https://github.com/orgtre/google-books-ngram-frequency>, the files
  `ngrams/1grams_english.csv`, `ngrams/1grams_spanish.csv` and `ngrams/1grams_french.csv`.
- Underlying data: the Google Books Ngram Corpus, version 3 (20200217), books published 2010 to 2019.
- License: Creative Commons Attribution 3.0 Unported (CC BY 3.0), <https://creativecommons.org/licenses/by/3.0/>.
- Changes made by gig.log: entries with any uppercase letter (proper nouns) dropped; entries split into letter runs and
  lowercased; the first 5,000 distinct runs per language kept; the three lists merged and sorted. The file's header
  records the counts and a sha256 of the list.

This credit implies no endorsement of gig.log by the list's author or by Google.
