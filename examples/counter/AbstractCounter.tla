---- MODULE AbstractCounter ----
\* The contract, written as a specification: each actor increments once, and an increment is one atomic step.
EXTENDS Naturals
CONSTANT Actors
VARIABLE value, done
Init == value = 0 /\ done = {}
Increment(a) == a \notin done /\ value' = value + 1 /\ done' = done \cup {a}
Spec == Init /\ [][\E a \in Actors : Increment(a)]_<<value, done>>
====
