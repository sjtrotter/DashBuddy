package cloud.trotter.dashbuddy.domain.model.vehicle

import org.junit.Assert.*
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class VehicleStorageTest {
    @Test fun `a v1 garage decodes into typed profiles with nothing lost`() {
        val rows = VehicleClass.entries.mapIndexed { index, cls ->
            """{"id":"v$index","name":null,"createdAt":100,"retiredAt":200,"futureVehicle":{"a":1},"profile.futurePart":"root sentinel","legacyExtras":7,"profile":{
                "year":"2021","make":"Honda","model":"Model","vehicleClass":"${cls.name}","mpg":42.0,"fuelType":"DIESEL",
                "tireSetCost":0.0,"tireLifetimeMi":12345.0,"oilCost":85.0,"oilIntervalMi":6000.0,
                "brakesCost":240.0,"brakesIntervalMi":30000.0,"fluidsCost":35.0,"fluidsIntervalMi":22000.0,
                "miscYearly":200.0,"miscYearlyMi":9000.0,"includeDepreciation":false,"purchasePrice":10000.0,
                "totalLifetimeMi":100000.0,"insuranceDeltaPerMonth":0.0,"registrationDeltaPerYear":75.0,
                "expectedAnnualMiles":18000.0,"futurePart":{"cost":99}}}"""
        }
        for (raw in listOf(rows.joinToString(",", "[", "]"), rows.joinToString(",", "{\"vehicles\":[", "]}"))) {
            val garage = VehicleStorage.decode(raw)!!
            assertEquals(VehicleClass.entries.size, garage.size)
            garage.zip(VehicleClass.entries).forEach { (vehicle, cls) ->
                val profile = vehicle.profile
                assertEquals(cls, profile.legacyClass)
                assertEquals("2021 Honda Model", vehicle.displayName)
                assertEquals(200L, vehicle.retiredAt)
                assertEquals(WearProfile(tireSetCost = 0.0, tireLifetimeMi = 12345.0, oilCost = 85.0,
                    oilIntervalMi = 6000.0, brakesCost = 240.0, brakesIntervalMi = 30000.0,
                    fluidsCost = 35.0, fluidsIntervalMi = 22000.0, miscYearly = 200.0, miscYearlyMi = 9000.0), profile.wear)
                assertEquals(FixedCosts(false, 10000.0, 100000.0, 0.0, 75.0, 18000.0), profile.fixed)
                assertEquals(kotlinx.serialization.json.Json.parseToJsonElement("{\"a\":1}"), vehicle.legacyExtras["futureVehicle"])
                assertEquals(kotlinx.serialization.json.Json.parseToJsonElement("{\"cost\":99}"), vehicle.legacyExtras["profile"]!!.jsonObject["futurePart"])
                assertEquals(kotlinx.serialization.json.JsonPrimitive("root sentinel"), vehicle.legacyExtras["profile.futurePart"])
                assertEquals(kotlinx.serialization.json.JsonPrimitive(7), vehicle.legacyExtras["legacyExtras"])
                if (cls.burnsFuel) {
                    assertEquals(42.0, profile.gasMpg)
                    assertEquals(FuelType.DIESEL, profile.gasFuel)
                } else {
                    assertNull(profile.gasMpg)
                    assertEquals(kotlinx.serialization.json.JsonPrimitive(42.0), vehicle.legacyExtras["profile"]!!.jsonObject["mpg"])
                    assertEquals(kotlinx.serialization.json.JsonPrimitive("DIESEL"), vehicle.legacyExtras["profile"]!!.jsonObject["fuelType"])
                }
                when (cls) {
                    VehicleClass.EV -> assertTrue(profile is ElectricCar && profile.kwhPer100Mi == null)
                    VehicleClass.MOTORCYCLE -> assertTrue(profile is Motorcycle)
                    VehicleClass.E_BIKE -> assertTrue(profile is EBike && profile.whPerMile == null)
                    else -> assertTrue(profile is GasCar)
                }
            }
            assertEquals(garage, VehicleStorage.decode(VehicleStorage.encode(garage)))
        }
    }

    @Test fun `a v2 garage round-trips`() {
        val profiles = listOf(GasCar(), HybridCar(mpg = 47.0), ElectricCar(kwhPer100Mi = 0.0), Motorcycle(),
            ScooterMoped(Powertrain.GAS), ScooterMoped(Powertrain.ELECTRIC, kwhPer100Mi = 2.5),
            EBike(wear = WearProfile(batteryCost = 0.0)), Bicycle())
        val vehicles = profiles.mapIndexed { i, profile -> Vehicle("$i", "Vehicle $i", i.toLong(), profile,
            legacyExtras = mapOf("arbitrary" to kotlinx.serialization.json.JsonPrimitive("kept"))) }
        val raw = VehicleStorage.encode(vehicles)
        assertTrue(raw.startsWith("{\"version\":2,\"vehicles\":["))
        assertTrue(raw.contains("\"type\":\"electric_car\""))
        assertEquals(vehicles, VehicleStorage.decode(raw))
    }

    @Test fun `old vehicle json uses a default profile and keeps its name`() {
        val old = """[{"id":"one","name":"Civic","createdAt":100}]"""
        val decoded = VehicleStorage.decode(old)!!.single()
        assertEquals("Civic", decoded.displayName)
        assertEquals(LegacyProfile().toTyped(), decoded.profile)
        assertNull(decoded.retiredAt)
    }

    @Test fun `unnamed car displays saved identity`() {
        val car = Vehicle("one", null, 100, LegacyProfile(year = "2020", make = "Honda", model = "Civic").toTyped())
        assertEquals("2020 Honda Civic", car.displayName)
        assertEquals(car, VehicleStorage.decode(VehicleStorage.encode(listOf(car)))!!.single())
    }
    @Test fun `unnamed car without identity displays its class`() {
        assertEquals("Truck", Vehicle("truck", null, 1,
            LegacyProfile(vehicleClass = VehicleClass.TRUCK).toTyped()).displayName)
    }
    @Test fun `round trips`() {
        val vehicles = listOf(Vehicle("one", "Civic", 100), Vehicle("two", "Truck", 200))
        assertEquals(vehicles, VehicleStorage.decode(VehicleStorage.encode(vehicles)))
    }
    @Test fun `a thirty code point name with emoji is valid`() {
        val name = "x".repeat(29) + "🚗"
        val vehicle = Vehicle("one", name, 100)
        assertEquals(Vehicle.MAX_NAME_CODE_POINTS, name.codePointCount(0, name.length))
        assertEquals(name, vehicle.name)
        assertEquals(listOf(vehicle), VehicleStorage.decode(VehicleStorage.encode(listOf(vehicle))))
        assertThrows(IllegalArgumentException::class.java) { Vehicle("two", name + "x", 100) }
    }
    @Test fun `malformed json decodes to null`() {
        listOf("broken", "{}", """[{"id":"one","name":" ","createdAt":0}]""",
            """[{"id":"good","name":"Civic","createdAt":0},{"id":"bad","name":" ","createdAt":0}]""").forEach {
            assertNull(VehicleStorage.decode(it))
        }
    }
    @Test fun `absent or blank input is an empty garage`() {
        listOf(null, "", "  ").forEach { assertEquals(emptyList<Vehicle>(), VehicleStorage.decode(it)) }
    }
}
