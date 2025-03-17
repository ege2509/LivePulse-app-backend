package com.ecgapp.ecgapp

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class EcgappApplication

fun main(args: Array<String>) {
	runApplication<EcgappApplication>(*args)
}
