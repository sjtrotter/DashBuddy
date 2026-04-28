package cloud.trotter.dashbuddy.rules

/** Thrown when a rule JSON cannot be compiled into a valid lambda. */
class RuleCompileException(message: String, cause: Throwable? = null) :
    Exception(message, cause)