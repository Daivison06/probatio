---- MODULE Lease ----
EXTENDS Naturals, FiniteSets
VARIABLE owner, started, finished, bad, guarded
Actors == {"A", "B", "C"}
vars == <<owner, started, finished, bad, guarded>>
Init == /\ owner = "" /\ started = {} /\ finished = {} /\ bad = FALSE /\ guarded \in BOOLEAN
InitBuggy == Init /\ guarded = FALSE
InitFixed == Init /\ guarded = TRUE
Acquire(a) ==
    /\ a \in Actors \ started /\ owner = ""
    /\ owner' = a /\ started' = started \cup {a}
    /\ UNCHANGED <<finished, bad, guarded>>
Expire ==
    /\ owner /= "" /\ owner' = ""
    /\ UNCHANGED <<started, finished, bad, guarded>>
Release(a) ==
    /\ a \in started \ finished
    /\ owner' = IF ~guarded \/ owner = a THEN "" ELSE owner
    /\ bad' = (bad \/ (~guarded /\ owner /= "" /\ owner /= a))
    /\ finished' = finished \cup {a}
    /\ UNCHANGED <<started, guarded>>
Next == (\E a \in Actors : Acquire(a) \/ Release(a)) \/ Expire \/ UNCHANGED vars
NoStaleDelete == ~bad
Observe == [owner |-> owner, guarded |-> guarded]
Step(e) == CASE e.action = "acquire" -> Acquire(e.actor)
            [] e.action = "expire" -> Expire
            [] e.action = "release" -> Release(e.actor)
            [] OTHER -> FALSE
====
