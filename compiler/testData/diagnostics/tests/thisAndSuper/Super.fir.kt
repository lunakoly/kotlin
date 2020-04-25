package example

interface T {
    fun foo() {}
}
open class C() {
    fun bar() {}
}

class A<E>() : C(), T {

    fun test() {
        super
        super<T>
        super.foo()
        super<T>.foo()
        super<C>.bar()
        super<T>@A.foo()
        super<C>@A.bar()
        super<E>.<!UNRESOLVED_REFERENCE!>bar<!>()
        super<E>@A.<!UNRESOLVED_REFERENCE!>bar<!>()
        <!NOT_A_SUPERTYPE!>super<Int><!>.<!UNRESOLVED_REFERENCE!>foo<!>()
        super<<!SYNTAX!><!>>.<!UNRESOLVED_REFERENCE!>foo<!>()
        <!NOT_A_SUPERTYPE!>super<() -> Unit><!>.<!UNRESOLVED_REFERENCE!>foo<!>()
        <!NOT_A_SUPERTYPE!>super<Unit><!>.<!UNRESOLVED_REFERENCE!>foo<!>()
        super<T>@B.foo()
        super<C>@B.bar()
    }

    inner class B : T {
        fun test() {
            super<T>.foo();
            <!NOT_A_SUPERTYPE!>super<C><!>.bar()
            <!NOT_A_SUPERTYPE!>super<C>@A<!>.bar()
            super<T>@A.foo()
            super<T>@B.foo()
            <!NOT_A_SUPERTYPE!>super<C>@B<!>.<!UNRESOLVED_REFERENCE!>foo<!>()
            super.foo()
            super
            super<T>
        }
    }
}

interface G<T> {
    fun foo() {}
}

class CG : G<Int> {
    fun test() {
        super<G>.foo() // OK
        super<G<<!TYPE_ARGUMENTS_REDUNDANT_IN_SUPER_QUALIFIER!>Int<!>>>.foo() // Warning
        super<G<<!INAPPROPRIATE_TYPE_ARGUMENT_IN_SUPER_QUALIFIER!>E<!>>>.foo() // Error
        super<G<<!INAPPROPRIATE_TYPE_ARGUMENT_IN_SUPER_QUALIFIER!>String<!>>>.foo() // Error
    }
}

// The case when no supertype is resolved
class ERROR<E>() : UR {

    fun test() {
        super.<!UNRESOLVED_REFERENCE!>foo<!>()
    }
}
