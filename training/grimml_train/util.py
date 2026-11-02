def anon_id_base36(n: int) -> str:
    if n is None:
        return ""
    if n < 0:
        n = n + (1 << 64)
    digits = "0123456789abcdefghijklmnopqrstuvwxyz"
    if n == 0:
        return "0"
    out: list[str] = []
    while n:
        n, r = divmod(n, 36)
        out.append(digits[r])
    return "".join(reversed(out))
