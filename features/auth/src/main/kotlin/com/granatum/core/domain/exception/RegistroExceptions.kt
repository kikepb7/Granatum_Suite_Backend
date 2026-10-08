package com.granatum.core.domain.exception

/*
 * Sign-up of the owner (feature 005, US1) and onboarding by an ADMIN (feature
 * 009). No message carries the email, name, document or code that caused it:
 * they reach clients and may reach logs (principle VI).
 */

/**
 * Wrong bootstrap code, none configured, or an ADMIN already exists. The three
 * causes answer the same on purpose: telling them apart would let anyone learn
 * whether an installation already has its ADMIN (005 research.md D-002).
 */
class CodigoArranqueInvalidoException :
    ForbiddenException("El código de arranque no es válido")

class DocumentoInvalidoException :
    InvalidOperationException("El documento de identidad no es un DNI ni un NIE válido")
