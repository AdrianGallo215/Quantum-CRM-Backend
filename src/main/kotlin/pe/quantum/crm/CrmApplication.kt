package pe.quantum.crm

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class CrmApplication

fun main(args: Array<String>) {
    runApplication<CrmApplication>(*args)
}
