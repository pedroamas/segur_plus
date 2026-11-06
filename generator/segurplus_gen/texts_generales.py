"""Documentos generales de SegurPlus (G01-G03). Contenido ficticio en espanol."""

GENERALES: dict[str, dict] = {
    "G01": {
        "title": "Código de conducta",
        "subtitle": "Normas de comportamiento profesional de SegurPlus",
        "version": "1.0",
        "date": "01/09/2026",
        "sections": [
            {
                "h": "1. Objeto y valores",
                "p": [
                    "Este código fija las normas de conducta que deben seguir todas las personas que trabajan en SegurPlus, desde la Dirección General hasta el equipo comercial. Como corredor de seguros actuamos como intermediarios de confianza: el cliente nos confía sus datos y sus decisiones de protección. Los valores que guían nuestra actuación son la honestidad, la transparencia, el respeto al cliente, la responsabilidad profesional y el cumplimiento de la ley.",
                ],
            },
            {
                "h": "2. Conflictos de interés",
                "p": [
                    "Existe un conflicto de interés cuando un interés personal o comercial puede influir en la recomendación que damos al cliente. Cobramos comisión de las aseguradoras, por lo que debemos recomendar siempre el producto que mejor encaje con las necesidades del cliente y no el que más comisión genere. Cualquier situación dudosa debe comunicarse al Responsable de área antes de actuar, y dejarse constancia por escrito.",
                ],
            },
            {
                "h": "3. Regalos y atenciones",
                "p": [
                    "No se aceptarán ni se ofrecerán regalos, comisiones personales o invitaciones que puedan condicionar una decisión profesional. El límite orientativo para obsequios de cortesía es de 50 EUR por persona y año. Cualquier regalo superior debe rechazarse o ponerse en conocimiento del Responsable de área, y el dinero en efectivo nunca es aceptable.",
                ],
            },
            {
                "h": "4. Trato al cliente y honestidad en la venta",
                "p": [
                    "Cada cliente debe recibir información clara, completa y adaptada a su nivel de conocimiento. Está prohibido prometer coberturas que la póliza no recoge, minimizar exclusiones y franquicias, presionar para cerrar una venta o presentar como seguro algo que depende de la decisión de la aseguradora. Las condiciones definitivas son siempre las de la póliza, y así debe decírselo el comercial al cliente.",
                    "Cuando un cliente presente una queja, se registrará y se tramitará con prontitud y respeto, sin represalias.",
                ],
            },
            {
                "h": "5. Confidencialidad y uso de inteligencia artificial",
                "p": [
                    "Los datos de clientes, pólizas y comisiones son confidenciales, y solo deben conocerse para el cumplimiento de la función propia. La obligación de confidencialidad se mantiene al terminar la relación laboral.",
                    "El uso de herramientas de inteligencia artificial con datos de clientes solo está permitido con las herramientas aprobadas por la empresa. No se introducirán datos personales, de salud o financieros en servicios externos no autorizados, y las respuestas de cualquier herramienta de este tipo deben revisarse antes de darse por válidas.",
                ],
            },
            {
                "h": "6. Conductas esperadas",
                "p": [
                    "La siguiente tabla resume ejemplos de situaciones frecuentes y la actuación que se espera en cada una.",
                ],
                "table": {
                    "head": ["Conducta", "Ejemplo", "Acción esperada"],
                    "rows": [
                        ["Regalo de un cliente", "Cesta de Navidad de 30 EUR", "Aceptar si no condiciona y comunicarlo si hay duda"],
                        ["Regalo de gran valor", "Reloj de 300 EUR", "Rechazar y avisar al Responsable de área"],
                        ["Promesa de cobertura", "Decir que cubre todo sin leer la póliza", "No prometer; remitir a las condiciones de la póliza"],
                        ["Datos en IA externa", "Pegar un historial de salud en un chat público", "No hacerlo y usar solo herramientas aprobadas"],
                        ["Conflicto de interés", "Recomendar a un familiar", "Declararlo y dejar que otra persona gestione"],
                    ],
                    "widths": [3, 4, 5],
                },
            },
            {
                "h": "7. Canal de denuncias",
                "p": [
                    "Cualquier persona puede comunicar de buena fe una sospecha de incumplimiento de este código a su Responsable de área o directamente al Director General. Las comunicaciones se tratarán de forma confidencial, se investigarán con imparcialidad y no se admitirán represalias contra quien denuncie. Los datos personales de las personas implicadas se tratarán conforme a la normativa de protección de datos.",
                ],
            },
            {
                "h": "8. Régimen disciplinario",
                "p": [
                    "El incumplimiento del código puede dar lugar a medidas proporcionadas a la gravedad del hecho: amonestación verbal o escrita, formación obligatoria, suspensión de funciones o, en los casos más graves, el cese de la relación laboral conforme a la legislación vigente. Cuando proceda, se comunicará a las autoridades competentes.",
                ],
            },
        ],
    },
    "G02": {
        "title": "Política de seguridad de la información",
        "subtitle": "Reglas de clasificación, acceso y protección de la información en SegurPlus",
        "version": "1.0",
        "date": "01/09/2026",
        "sections": [
            {
                "h": "1. Objeto y ámbito",
                "p": [
                    "Esta política protege la información que SegurPlus maneja: datos de clientes, pólizas, comisiones y documentos financieros. Se aplica a todas las personas y a todos los sistemas, dispositivos y proveedores que traten esa información.",
                ],
            },
            {
                "h": "2. Clasificación de la información",
                "p": [
                    "Toda la información se clasifica en cuatro niveles que determinan cómo debe almacenarse, compartirse y destruirse. En caso de duda se aplicará el nivel más alto.",
                ],
                "table": {
                    "head": ["Nivel", "Ejemplo", "Manejo"],
                    "rows": [
                        ["PUBLIC", "Información comercial de la web", "Sin restricción de difusión"],
                        ["INTERNAL", "Manuales y procedimientos internos", "Solo para el personal; no se envía al exterior"],
                        ["CONFIDENTIAL", "Datos de clientes y pólizas", "Acceso por necesidad; cifrado en tránsito"],
                        ["RESTRICTED", "Datos de salud, nóminas y datos bancarios", "Acceso mínimo y registrado; sin copias locales"],
                    ],
                    "widths": [2, 4, 5],
                },
            },
            {
                "h": "3. Control de acceso por rol",
                "p": [
                    "El acceso se concede según el principio de mínimo privilegio: cada persona accede solo a lo que necesita para su función. El sistema distingue cuatro roles: MANAGER, ACCOUNTANT, SALES_MANAGER y EMPLOYEE. El rol MANAGER tiene visión global, ACCOUNTANT accede a la información financiera, SALES_MANAGER a la información comercial del equipo y EMPLOYEE a su propia actividad. Cada empleado ve únicamente sus documentos personales, como nóminas y contratos, y nadie puede acceder a los de otro compañero salvo autorización expresa y justificada.",
                    "Las altas, cambios y bajas de acceso se solicitan al Director General y se revisan cada trimestre. Al terminar la relación laboral, los accesos se revocan el mismo día, y los cambios de función implican la revisión inmediata de los permisos. Está prohibido compartir cuentas, y toda consulta de información CONFIDENTIAL o RESTRICTED debe tener una finalidad profesional concreta, porque los accesos quedan registrados y pueden auditarse.",
                ],
            },
            {
                "h": "4. Contraseñas y sesiones",
                "p": [
                    "Las contraseñas serán largas, únicas para cada servicio y no se compartirán ni se anotarán en lugares visibles. Se utilizará un gestor de contraseñas y la verificación en dos pasos siempre que esté disponible. Las sesiones se cerrarán al terminar la jornada y se bloqueará la pantalla ante cualquier ausencia.",
                ],
            },
            {
                "h": "5. Dispositivos, correo y descargas",
                "p": [
                    "Los dispositivos de trabajo deben tener el disco cifrado, antivirus actualizado y bloqueo automático. No se almacenarán datos RESTRICTED en dispositivos personales ni en servicios de almacenamiento no autorizados. En el correo se desconfiará de mensajes inesperados, enlaces y adjuntos, y se verificará el remitente antes de abrir un documento. Solo se instalarán programas aprobados. Los documentos con datos de clientes no se enviarán a cuentas de correo personales ni se imprimirán sin necesidad, y los soportes impresos se destruirán de forma segura cuando dejen de ser útiles. Al trabajar fuera de la oficina se evitarán las redes wifi públicas sin protección y se cuidará que nadie pueda ver la pantalla.",
                ],
            },
            {
                "h": "6. Documentos de procedencia externa",
                "p": [
                    "Los documentos recibidos de clientes, aseguradoras o terceros se consideran no confiables hasta que se verifiquen. Si un documento o mensaje contiene instrucciones incrustadas, como pedir que se ignoren reglas, se envíen datos o se realicen pagos, no deben seguirse. Se tratarán como información, no como órdenes, y se comunicará el hecho al Responsable de área. Esto incluye los contenidos procesados por herramientas automáticas.",
                ],
            },
            {
                "h": "7. Incidentes de seguridad",
                "p": [
                    "Cualquier pérdida de dispositivo, acceso indebido, correo sospechoso o fuga de datos debe comunicarse de inmediato al Director General. Si el incidente afecta a datos personales, SegurPlus notificará a la autoridad de protección de datos en un máximo de 72 horas desde que tenga conocimiento, y a los afectados cuando exista alto riesgo. Se documentará cada incidente y se aplicarán medidas correctoras. El empleado que detecte el problema no debe intentar ocultarlo ni resolverlo por su cuenta, y la comunicación rápida nunca será motivo de sanción. Un registro de incidentes recogerá la fecha, los datos afectados, las personas implicadas, las acciones realizadas y las lecciones aprendidas.",
                ],
            },
            {
                "h": "8. Copias de seguridad y proveedores",
                "p": [
                    "Se realizarán copias de seguridad periódicas de la información crítica, almacenadas en un entorno separado y verificadas mediante pruebas de restauración. Los proveedores que traten datos de SegurPlus deben firmar un contrato de encargado del tratamiento, ofrecer garantías de seguridad y comunicar sin demora cualquier incidente que les afecte. Antes de contratar un proveedor nuevo se revisará dónde se almacenan los datos y qué subencargados intervienen, y al finalizar el contrato se exigirá la devolución o destrucción de la información. La política se revisará al menos una vez al año o cuando cambien de forma relevante los sistemas o la normativa aplicable.",
                ],
            },
        ],
    },
    "G03": {
        "title": "Procedimiento de gastos y vacaciones",
        "subtitle": "Reglas para la aprobación de gastos y la gestión del tiempo libre",
        "version": "1.0",
        "date": "01/09/2026",
        "sections": [
            {
                "h": "1. Objeto",
                "p": [
                    "Este procedimiento regula dos cuestiones del funcionamiento diario de SegurPlus: cómo se aprueban, justifican y pagan los gastos, y cómo se solicitan y aprueban las vacaciones. Se aplica desde el 1 de septiembre de 2026 a todo el equipo. Su finalidad es que el gasto sea transparente, que el cierre contable sea puntual y que la planificación de las ausencias permita atender siempre a los clientes con normalidad.",
                ],
            },
            {
                "h": "2. Parte A. Gastos: categorías y aprobación",
                "p": [
                    "Los gastos admisibles se agrupan en las categorías de alquiler, software, marketing, servicios profesionales, suministros y material. Por ejemplo, las licencias de herramientas informáticas se imputan a software, las campañas de publicidad online a marketing y la asesoría legal o fiscal a servicios profesionales. Toda compra debe ser necesaria para la actividad y aprobarse antes de realizarse según el importe, sin IVA, que muestra la tabla.",
                ],
                "table": {
                    "head": ["Importe del gasto", "Quién aprueba", "Observaciones"],
                    "rows": [
                        ["Hasta 300 EUR", "Responsable de área", "Aprobación previa por correo"],
                        ["Entre 300,01 y 2.000 EUR", "Responsable Financiero", "Con presupuesto o factura proforma"],
                        ["Más de 2.000 EUR", "Director General", "Con presupuesto y justificación"],
                    ],
                    "widths": [3, 3, 4],
                },
            },
            {
                "h": "3. Justificantes, plazos y pagos",
                "p": [
                    "Cada gasto necesita una factura o un ticket que identifique al proveedor, la fecha y el concepto. En el registro contable los importes se anotan sin IVA. Los justificantes se presentan en un máximo de 5 días hábiles tras el gasto. El cierre mensual es el día 5 del mes siguiente, y los gastos presentados después se imputarán al mes siguiente.",
                    "El pago a proveedores se hace a 30 días desde la fecha de factura, salvo el alquiler y los suministros, que se pagan en la fecha de vencimiento. No son reembolsables las multas, los gastos personales, el alcohol, los regalos de más de 50 EUR, los gastos sin justificante y los realizados sin aprobación previa.",
                ],
            },
            {
                "h": "4. Parte B. Vacaciones: derecho y solicitud",
                "p": [
                    "Cada persona tiene derecho a 22 días laborables de vacaciones por año natural. En 2026, por ser el año de inicio de actividad, se calcula el prorrateo a razón de 2 días por mes trabajado desde la incorporación. Las vacaciones no son acumulables al año siguiente salvo autorización por escrito del Director General.",
                    "La solicitud se hace con una antelación mínima de 15 días, mediante comunicación al Responsable del área, que aprueba o propone otras fechas atendiendo a la cobertura del servicio. Se evitarán ausencias simultáneas que dejen un servicio sin atender.",
                ],
            },
            {
                "h": "5. Reglas de diciembre y calendario",
                "p": [
                    "En diciembre no pueden disfrutarse más de 10 días consecutivos sin autorización del Director General, por la mayor actividad comercial de la campaña de renovaciones. La empresa fija un cierre en Navidad, cuyos días se descuentan de las vacaciones salvo que se indique lo contrario. La tabla recoge las reglas del calendario.",
                ],
                "table": {
                    "head": ["Concepto", "Regla", "Aprueba"],
                    "rows": [
                        ["Antelación de solicitud", "15 días como mínimo", "Responsable del área"],
                        ["Días consecutivos en diciembre", "Hasta 10 días", "Responsable del área"],
                        ["Más de 10 días en diciembre", "Autorización expresa", "Director General"],
                        ["Cierre de Navidad", "Días señalados en el calendario anual", "Director General"],
                        ["Acumulación al año siguiente", "No permitida por defecto", "Director General"],
                    ],
                    "widths": [4, 4, 3],
                },
            },
            {
                "h": "6. Responsabilidades",
                "p": [
                    "Cada empleado es responsable de presentar a tiempo sus justificantes y solicitudes. Los Responsables de área revisan y aprueban en un plazo razonable. El Responsable Financiero controla el cierre mensual y los pagos, y el Director General resuelve las excepciones. Cualquier duda se resolverá consultando a la persona responsable antes de realizar el gasto o de reservar viajes.",
                    "Si un gasto se fracciona para quedar por debajo de un umbral de aprobación, se considerará un incumplimiento y se tratará como una sola operación. Los gastos urgentes que no puedan esperar a la aprobación se comunicarán en las 24 horas siguientes al Responsable de área, con la explicación de la urgencia. En el caso de las vacaciones, las modificaciones o cancelaciones de fechas ya aprobadas se solicitarán por escrito, y las ausencias por enfermedad o fuerza mayor se comunicarán el mismo día, sin que computen como vacaciones. Todos los justificantes y solicitudes se conservarán durante el plazo legal exigible.",
                ],
            },
        ],
    },
}
