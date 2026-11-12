# Controlled generation order for the technical audit

The previous run 43171328114 was rejected, not published. The fresh seed-42 candidate differed at two sampled coal-ore blocks; one ordinary-world reference repeat differed at 298 sampled blocks. The latter occurred without changing the game mod. These observations do not prove a mod regression or prove its absence.

The new audit prepares a wider TERRAIN halo, then advances native chunk statuses in a declared order before observing FULL columns. It changes only the private test observer, not the game mod. All existing block comparisons, palette checks, climate checks, reference-repeat thresholds and release gates remain active.

The ordinary-world equality window is fixed at (8192,8192), outside startup spawn preparation, rather than (0,0). This coordinate is declared before running the new experiment and is not selected by a search for passing blocks. FreshGenerator windows are unchanged. On initial generation a target already past TERRAIN aborts the test; saved-world reload deliberately permits existing FULL chunks. The existing separate structural audit still checks spawn areas.

The actual native stage list, scheduled-request SHA-256, request counts and predecorated-target counts are retained in each raw observation. The 14 observer-transformation tests check missing/duplicate anchors, repeatability, preserved phase metadata and the absence of block-writing code. Compilation and native-world outcomes must still be checked; this document is not a claim that the new run passed.
