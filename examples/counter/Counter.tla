---- MODULE Counter ----
EXTENDS Naturals, FiniteSets
Actors == {"A", "B"}
VARIABLE value, local, read, done, atomic
vars == <<value, local, read, done, atomic>>
Init == /\ value = 0 /\ local = [a \in Actors |-> 0] /\ read = {} /\ done = {} /\ atomic \in BOOLEAN
InitBuggy == Init /\ atomic = FALSE
InitFixed == Init /\ atomic = TRUE
Read(a) ==
    /\ ~atomic /\ a \in Actors \ read
    /\ local' = [local EXCEPT ![a] = value] /\ read' = read \cup {a}
    /\ UNCHANGED <<value, done, atomic>>
Write(a) ==
    /\ ~atomic /\ a \in read \ done
    /\ value' = local[a] + 1 /\ done' = done \cup {a}
    /\ UNCHANGED <<local, read, atomic>>
Increment(a) ==
    /\ atomic /\ a \in Actors \ done
    /\ value' = value + 1 /\ done' = done \cup {a}
    /\ UNCHANGED <<local, read, atomic>>
Next == (\E a \in Actors : Read(a) \/ Write(a) \/ Increment(a)) \/ UNCHANGED vars
NoLostUpdate == value = Cardinality(done)
Observe == [value |-> value, atomic |-> atomic]
Step(e) == CASE e.action = "read" -> Read(e.actor)
            [] e.action = "write" -> Write(e.actor)
            [] e.action = "increment" -> Increment(e.actor)
            [] OTHER -> FALSE
====
