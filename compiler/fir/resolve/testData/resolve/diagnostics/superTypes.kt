open class classA (string1: String){
    val string2: String
    init {
        string2 = string1
    }
}

open class classB : IA {

}

open class classC {

}

class classD {

}

<!MANY_CLASSES_IN_SUPERTYPE_LIST!>class classManyClasses : classB, classC {

}<!>

<!SUPERTYPE_APPEARS_TWICE!>class classSupertypeTwice : IA, IA {

}<!>

class classSupertypeNotInited : classA {

}

<!FINAL_SUPERTYPE!>class classFinalSupertype : classD {

}<!>

<!SINGLETON_IN_SUPERTYPE!>class classWithSingleton : OSingleton {

}<!>

interface IA {

}

<!INTERFACE_WITH_SUPERCLASS!>interface IwithSuperclass : classA {
    fun t()
}<!>

open enum class enumA {
    eA,
    eB,
}

<!CLASS_IN_SUPERTYPE_FOR_ENUM!>enum class enumWithClass : classA {
    eA,
    eB,
}<!>

object OSingleton {
    fun foo() {

    }
}