"""Add Context constructors to the renderer while keeping existing callers valid."""

SERVICE = "Lio/dynamicmotion/zoryx/services/OverlayService;"
CONTEXT = "Landroid/content/Context;"
CONSTRUCTOR = ".method public constructor "


def adapt_renderer(smali_root):
    add_context_constructor(smali_root, "o", "Ll9/r;", 3)
    add_context_constructor(
        smali_root, "r", "", 2,
        old_call=f"Ll9/o;-><init>({SERVICE}Ll9/r;)V",
        new_call=f"Ll9/o;-><init>({CONTEXT}Ll9/r;)V",
    )
    add_context_constructor(smali_root, "h", "FLandroid/util/DisplayMetrics;Ll9/c0;", 5)


def add_context_constructor(smali_root, class_name, tail, registers, *, old_call=None, new_call=None):
    path = smali_root / "l9" / (class_name + ".smali")
    original = path.read_text(encoding="utf-8")
    legacy = f"<init>({SERVICE}{tail})V"
    native = f"<init>({CONTEXT}{tail})V"
    signature = CONSTRUCTOR + legacy
    if original.count(signature) != 1:
        raise ValueError(f"Construtor inesperado em {path.name}")

    start = original.index(signature)
    end = original.index(".end method", start)
    method = original[start:end].replace(signature, CONSTRUCTOR + native, 1)
    if old_call is not None:
        if method.count(old_call) != 1:
            raise ValueError(f"Chamada de construtor inesperada em {path.name}")
        method = method.replace(old_call, new_call, 1)

    operands = ", ".join(f"p{i}" for i in range(registers))
    wrapper = (
        f"\n{signature}\n"
        f"    .registers {registers}\n\n"
        f"    invoke-direct {{{operands}}}, Ll9/{class_name};->{native}\n\n"
        "    return-void\n.end method\n"
    )
    path.write_text(original[:start] + method + original[end:] + wrapper, encoding="utf-8")
