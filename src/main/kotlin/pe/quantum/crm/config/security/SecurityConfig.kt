package pe.quantum.crm.config.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource

/**
 * Configuracion de seguridad base (SECURITY-backend.md §2, §3, §6, §7).
 *
 * - Stateless: sin sesion HTTP; la identidad viene del JWT en cada request.
 * - Las rutas de `/api/v1/auth` y el health check son publicas; el resto exige auth.
 * - Cabeceras de seguridad HTTP en toda respuesta (§6).
 * - CORS restrictivo: solo los origenes de `CORS_ALLOWED_ORIGINS`, con credenciales.
 * - BCrypt (cost 12) para hashear contraseñas (§2.3).
 * - CSRF deshabilitado: API stateless con tokens; las cookies usan SameSite=Strict
 *   (§5), que mitiga CSRF sin estado de servidor.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig(
    private val jwtService: JwtService,
    private val corsProperties: CorsProperties,
) {
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder(BCRYPT_STRENGTH)

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .cors { it.configurationSource(corsConfigurationSource()) }
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers("/api/v1/auth/**", "/actuator/health").permitAll()
                it.anyRequest().authenticated()
            }
            .headers { headers ->
                headers.contentSecurityPolicy { it.policyDirectives("default-src 'self'; frame-ancestors 'none'") }
                headers.frameOptions { it.deny() }
                headers.httpStrictTransportSecurity { it.includeSubDomains(true).maxAgeInSeconds(HSTS_MAX_AGE_SECONDS) }
                headers.referrerPolicy { it.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN) }
                headers.permissionsPolicy { it.policy("geolocation=(), microphone=(), camera=()") }
            }
            .exceptionHandling { it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)) }
            .addFilterBefore(
                JwtAuthenticationFilter(jwtService),
                UsernamePasswordAuthenticationFilter::class.java,
            )
            .addFilterAfter(
                MdcLoggingFilter(),
                JwtAuthenticationFilter::class.java,
            )
        return http.build()
    }

    private fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration()
        configuration.allowedOrigins = corsProperties.allowedOrigins
        configuration.allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        configuration.allowedHeaders = listOf("*")
        configuration.allowCredentials = true
        // Misma politica para todas las rutas.
        return CorsConfigurationSource { configuration }
    }

    private companion object {
        const val BCRYPT_STRENGTH = 12
        const val HSTS_MAX_AGE_SECONDS = 31_536_000L
    }
}
