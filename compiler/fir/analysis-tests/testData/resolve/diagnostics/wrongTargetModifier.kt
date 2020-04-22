class fields {
    val a: Int = 3

    val b: Boolean get() = a == 4

    var c: Int = 0

    var d: Boolean = false
        get() = c == 4

    var e: Int = 2
        set(value) {
            if (value > 0) field = value
        }
}


object A {
    inner class E {

    }

    fun foo() {
        inner class F {

        }

        class G {

        }
    }
}

class B {
    companion object {

    }
}

class C {
    companion object D {

    }
}

