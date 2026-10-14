package com.eora.d2resolver;

/** Three-valued logic: T(rue), F(alse), U(nknown -- depends on repository or user state we cannot see offline). */
public enum Tri {
    T, F, U;

    public static Tri of(boolean b) {
        return b ? T : F;
    }

    public Tri and(Tri o) {
        if (this == F || o == F) return F;
        if (this == U || o == U) return U;
        return T;
    }

    public Tri or(Tri o) {
        if (this == T || o == T) return T;
        if (this == U || o == U) return U;
        return F;
    }

    public Tri not() {
        return switch (this) {
            case T -> F;
            case F -> T;
            case U -> U;
        };
    }
}
