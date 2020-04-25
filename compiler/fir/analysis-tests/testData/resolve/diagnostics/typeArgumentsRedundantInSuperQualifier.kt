open class Returner<T>(val value: T) {
    open fun it(): T = value
}

class A : Returner<Int>(5) {
    override fun it(): Int {
        super<Returner<<!INAPPROPRIATE_TYPE_ARGUMENT_IN_SUPER_QUALIFIER!>String<!>>>.it()
        super<Returner<<!INAPPROPRIATE_TYPE_ARGUMENT_IN_SUPER_QUALIFIER!>E<!>>>.it()
        return super<Returner<<!TYPE_ARGUMENTS_REDUNDANT_IN_SUPER_QUALIFIER!>Int<!>>>.it()
    }
}

class B : <!INAPPLICABLE_CANDIDATE!>Returner<E><!>(5) {
    override fun it(): E {
        return super<Returner<<!INAPPROPRIATE_TYPE_ARGUMENT_IN_SUPER_QUALIFIER!>E<!>>>.it()
    }
}