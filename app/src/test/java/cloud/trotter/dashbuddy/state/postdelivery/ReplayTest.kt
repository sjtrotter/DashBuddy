//package cloud.trotter.dashbuddy.state.postdelivery
//
//import cloud.trotter.dashbuddy.state.StateFlowSimulator
//import org.junit.Test
//
//class ReplayTest {
//    @Test
//    fun replay_session_from_file() {
//        val engine = StateFlowSimulator()
//
//        // You can put "all.txt" in your src/test/resources folder
//        val logContent = this::class.java.classLoader
//            ?.getResource("all.txt")
//            ?.readText()
//            ?: throw RuntimeException("Log file not found!")
//
//        engine.runSimulation(logContent)
//    }
//}