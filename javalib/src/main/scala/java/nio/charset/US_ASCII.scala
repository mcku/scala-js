package java.nio.charset

private[charset] object US_ASCII extends ISO_8859_1_And_US_ASCII_Common(
    "US-ASCII", Array(
    "cp367", "ascii7", "ISO646-US", "646", "csASCII", "us", "iso_646.irv:1983",
    "ISO_646.irv:1991", "IBM367", "ASCII", "default", "ANSI_X3.4-1986",
    "ANSI_X3.4-1968", "iso-ir-6"),
    maxValue = 0x7f)