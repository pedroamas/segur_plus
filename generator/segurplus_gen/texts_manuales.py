"""Manuales de producto de SegurPlus (D26-D29). Contenido ficticio en espanol."""

MANUALES: dict[str, dict] = {
    "D26": {
        "title": "Manual de seguros de Auto",
        "subtitle": "Guía de trabajo para el equipo comercial de SegurPlus",
        "version": "1.1",
        "date": "01/09/2026",
        "sections": [
            {
                "h": "1. Alcance y papel de SegurPlus",
                "p": [
                    "Este manual describe cómo se comercializa el seguro de automóvil en SegurPlus. Somos un corredor de seguros: no asumimos el riesgo ni gestionamos los siniestros, que corresponden a la aseguradora. Nuestro valor está en comparar las ofertas de Aseguradora Ibérica y Seguros Atlántico, recomendar la modalidad adecuada y acompañar al cliente durante la vigencia de la póliza. Nuestro ingreso es la comisión que paga el asegurador, y esto debe comunicarse al cliente cuando lo solicite.",
                    "Los importes y límites que aparecen en este documento son orientativos e ilustrativos. Las condiciones definitivas de cada contrato son siempre las que figuran en la póliza emitida por la aseguradora.",
                ],
            },
            {
                "h": "2. Coberturas y exclusiones",
                "p": [
                    "La única cobertura legalmente obligatoria es la responsabilidad civil de suscripción obligatoria, que responde de los daños personales y materiales causados a terceros. El resto de garantías son opcionales y se añaden según la modalidad elegida y el valor del vehículo.",
                ],
                "h2": [
                    {
                        "h": "2.1 Garantías opcionales habituales",
                        "p": [
                            "Entre las garantías que más se contratan están la defensa jurídica y reclamación de daños, la asistencia en viaje, la rotura de lunas, el robo, el incendio, los daños propios por colisión y el vehículo de sustitución. Cada una tiene límites y carencias propias que el comercial debe leer antes de presentarlas.",
                        ],
                    },
                    {
                        "h": "2.2 Exclusiones habituales",
                        "p": ["Las aseguradoras suelen excluir determinados supuestos que conviene explicar siempre de forma expresa:"],
                        "bullets": [
                            "Conducción bajo los efectos del alcohol o las drogas por encima de los límites legales.",
                            "Conductor sin permiso válido o no declarado en la póliza.",
                            "Participación en competiciones, pruebas o carreras.",
                            "Daños derivados de un uso distinto al declarado, por ejemplo uso profesional si se contrató uso particular.",
                            "Daños por fenómenos extraordinarios que cubre otro organismo, salvo pacto expreso.",
                        ],
                    },
                ],
            },
            {
                "h": "3. Comparativa de modalidades",
                "p": [
                    "Las aseguradoras ofrecen tres niveles de protección. La siguiente tabla resume sus diferencias de forma orientativa para facilitar la conversación con el cliente.",
                ],
                "table": {
                    "head": ["Cobertura", "Terceros", "Terceros ampliado", "Todo riesgo con franquicia"],
                    "rows": [
                        ["Responsabilidad civil obligatoria", "Incluida", "Incluida", "Incluida"],
                        ["Defensa jurídica y asistencia en viaje", "Opcional", "Incluida", "Incluida"],
                        ["Rotura de lunas", "No", "Incluida", "Incluida"],
                        ["Robo e incendio", "No", "Incluido", "Incluido"],
                        ["Daños propios por colisión", "No", "No", "Incluidos, con franquicia"],
                        ["Franquicia orientativa", "No aplica", "No aplica", "150 a 500 EUR"],
                        ["Perfil recomendado", "Vehículo antiguo y bajo valor", "Vehículo de valor medio", "Vehículo nuevo o financiado"],
                    ],
                    "widths": [3, 2, 2, 3],
                },
            },
            {
                "h": "4. Perfiles de cliente y preguntas de recomendación",
                "p": [
                    "Antes de proponer una modalidad, el comercial debe conocer el uso real del vehículo y la situación del cliente. Una recomendación sin estas respuestas es una venta a ciegas y genera reclamaciones posteriores. Los datos se recogen respetando el principio de minimización de la normativa de protección de datos.",
                ],
                "bullets": [
                    "Marca, modelo, año de matriculación y valor aproximado del vehículo.",
                    "Si está financiado o en renting, pues la entidad suele exigir cobertura a todo riesgo.",
                    "Uso del vehículo: particular, desplazamiento al trabajo o profesional, y kilometraje anual.",
                    "Lugar de estacionamiento habitual: garaje cerrado, calle u otro.",
                    "Antigüedad del permiso del conductor principal y existencia de conductores ocasionales jóvenes.",
                    "Siniestros declarados en los últimos cinco años y bonificación acumulada.",
                ],
            },
            {
                "h": "5. Contratación, documentación y plazos",
                "p": [
                    "El proceso empieza con la recogida de datos del tomador y del vehículo, continúa con la comparación de ofertas entre las aseguradoras disponibles, la entrega de la información precontractual y termina con la firma y la emisión de la póliza. El comercial no debe emitir sin haber confirmado que el cliente ha entendido las exclusiones y la franquicia.",
                    "La documentación necesaria es el documento de identidad del tomador, el permiso de conducir del conductor habitual, la ficha técnica y el permiso de circulación del vehículo, y el último recibo o certificado de la póliza anterior para acreditar la bonificación.",
                    "En cuanto a plazos, el periodo de gracia de pago del primer recibo es de un mes: pasado ese tiempo sin abonar, la cobertura queda suspendida. Para evitar la renovación automática el cliente debe comunicarlo con un mes de preaviso. En seguros de auto no existe el derecho de desistimiento de 14 días propio de vida y salud contratados a distancia, por lo que conviene aclarar al cliente su verdadero margen de arrepentimiento antes de emitir.",
                ],
            },
            {
                "h": "6. Cancelaciones y renovaciones",
                "p": [
                    "Cuando un cliente solicita la baja, el comercial debe registrar la fecha y el motivo, y avisar a la aseguradora. Si la cancelación se produce dentro de los primeros 60 días desde la emisión, la aseguradora regulariza la comisión y SegurPlus puede tener que devolver la parte proporcional ya percibida. Por eso conviene preguntar el motivo e intentar ofrecer una alternativa antes de tramitar la baja.",
                    "Un mes antes del vencimiento debe revisarse cada póliza para valorar si la modalidad sigue siendo adecuada, si ha cambiado el uso o el valor del vehículo, y si las aseguradoras han modificado sus primas. La renovación es una oportunidad de servicio, no un trámite automático.",
                ],
            },
            {
                "h": "7. Tabla orientativa de límites y franquicias",
                "p": [
                    "Los valores siguientes son ilustrativos y sirven para orientar al cliente. No constituyen una oferta y pueden variar entre aseguradoras y productos.",
                ],
                "table": {
                    "head": ["Garantía", "Límite orientativo", "Franquicia típica"],
                    "rows": [
                        ["Responsabilidad civil obligatoria (daños personales)", "Conforme al límite legal vigente", "Sin franquicia"],
                        ["Responsabilidad civil voluntaria", "Hasta 50 millones EUR", "Sin franquicia"],
                        ["Rotura de lunas", "Valor de reparación o sustitución", "0 a 50 EUR"],
                        ["Daños propios", "Valor venal del vehículo", "150 a 500 EUR"],
                        ["Asistencia en viaje", "Desde el kilómetro 0 o 25", "Sin franquicia"],
                        ["Vehículo de sustitución", "7 a 15 días", "Sin franquicia"],
                    ],
                    "widths": [4, 3, 2],
                },
            },
            {
                "h": "8. Obligaciones, errores frecuentes y checklist",
                "p": [
                    "Como mediador debemos entregar la información precontractual antes de la contratación, identificar la aseguradora y la modalidad, informar de nuestra forma de remuneración y de cualquier conflicto de interés, y tratar los datos personales solo para la finalidad declarada. Si el cliente pregunta con qué aseguradoras trabajamos o cuánto cobramos, debemos responder con claridad.",
                    "Los errores más habituales del comercial son prometer coberturas que la póliza no incluye, olvidar mencionar la franquicia, recomendar siempre la aseguradora de mayor comisión sin valorar la mejor opción para el cliente, declarar un uso del vehículo inexacto para abaratar la prima, y emitir sin comprobar al conductor habitual. Una declaración inexacta puede dar lugar a que la aseguradora reduzca o rechace la indemnización.",
                    "Antes de emitir, repase esta lista de comprobación:",
                ],
                "bullets": [
                    "Datos del tomador, del conductor habitual y del vehículo verificados con documentos.",
                    "Uso, kilometraje y lugar de estacionamiento declarados correctamente.",
                    "Modalidad, franquicia y exclusiones explicadas y entendidas por el cliente.",
                    "Información precontractual entregada y registrada.",
                    "Consentimiento de protección de datos recogido.",
                    "Forma de pago y fecha de vencimiento confirmadas.",
                ],
            },
        ],
    },
    "D27": {
        "title": "Manual de seguros de Hogar",
        "subtitle": "Guía de trabajo para el equipo comercial de SegurPlus",
        "version": "1.1",
        "date": "01/09/2026",
        "sections": [
            {
                "h": "1. Alcance y papel de SegurPlus",
                "p": [
                    "Este manual orienta la comercialización del seguro de hogar. SegurPlus actúa como corredor: compara las propuestas de Aseguradora Ibérica y Mutua Levante, asesora al cliente y le acompaña durante la vigencia, pero no asume el riesgo ni tramita los siniestros, que gestiona la aseguradora. Nuestra remuneración es la comisión que el asegurador paga por la póliza, y debemos informar de ello cuando el cliente lo pregunte.",
                    "Las cifras que aparecen son orientativas e ilustrativas. Las condiciones definitivas son las de la póliza emitida por la aseguradora.",
                ],
            },
            {
                "h": "2. Coberturas y exclusiones",
                "p": [
                    "El seguro de hogar protege el continente, es decir, la estructura, instalaciones y elementos fijos de la vivienda, y el contenido, que son el mobiliario, los enseres y los objetos personales. La responsabilidad civil familiar cubre los daños que el asegurado cause a terceros. Ninguna garantía es obligatoria por ley, aunque una entidad financiera suele exigir el seguro de incendio sobre el continente cuando hay una hipoteca.",
                ],
                "h2": [
                    {
                        "h": "2.1 Coberturas básicas y opcionales",
                        "p": [
                            "Las garantías básicas suelen ser incendio, explosión, daños por agua, fenómenos atmosféricos, robo y responsabilidad civil. Como opcionales se ofrecen los daños eléctricos, la rotura de cristales, el equipo electrónico, los objetos de valor, la asistencia de hogar y la protección jurídica.",
                        ],
                    },
                    {
                        "h": "2.2 Exclusiones habituales",
                        "p": ["Estas exclusiones deben comunicarse expresamente al cliente antes de emitir:"],
                        "bullets": [
                            "Daños causados intencionadamente por el asegurado.",
                            "Desgaste, humedad progresiva y falta de mantenimiento.",
                            "Robo sin signos de violencia o con puertas abiertas, salvo pacto.",
                            "Objetos de valor por encima del límite pactado si no se han declarado.",
                            "Vivienda deshabitada durante más de un periodo determinado.",
                        ],
                    },
                ],
            },
            {
                "h": "3. Perfiles de cliente y preguntas de recomendación",
                "p": [
                    "El perfil determina el producto. Un propietario con hipoteca necesita continente; un inquilino suele necesitar sobre todo contenido y responsabilidad civil; el dueño de una segunda residencia debe valorar la desocupación. Para recomendar bien hay que preguntar y registrar las respuestas.",
                ],
                "bullets": [
                    "Si el cliente es propietario, inquilino o usufructuario, y si hay hipoteca.",
                    "Superficie construida, año de construcción y tipo de vivienda.",
                    "Vivienda habitual o segunda residencia, y tiempo que permanece vacía.",
                    "Valor de reconstrucción del inmueble y valor estimado del contenido.",
                    "Existencia de joyas, obras de arte, bicicletas o equipos electrónicos de valor.",
                    "Medidas de seguridad instaladas, como puerta blindada o alarma.",
                ],
            },
            {
                "h": "4. Contratación, documentación y plazos",
                "p": [
                    "El proceso incluye la recogida de datos de la vivienda, la comparación entre las aseguradoras disponibles, la entrega de la información precontractual, la firma y la emisión. El capital asegurado debe corresponder al valor real: un infraseguro provoca que la aseguradora aplique la regla proporcional y pague solo una parte del daño.",
                    "La documentación habitual es el documento de identidad del tomador, la dirección completa y la referencia catastral cuando se disponga de ella, la escritura o el contrato de alquiler, y la póliza anterior si existe.",
                    "El periodo de gracia para el pago del primer recibo es de un mes. El preaviso para evitar la renovación automática es de un mes antes del vencimiento. No existe desistimiento de 14 días con carácter general en hogar, salvo que lo establezca el contrato o la normativa de contratación a distancia aplicable.",
                ],
            },
            {
                "h": "5. Tabla orientativa de coberturas, límites y franquicias",
                "p": [
                    "Los importes siguientes son ilustrativos y no constituyen una oferta. Cada aseguradora fija sus propios límites y franquicias en la póliza.",
                ],
                "table": {
                    "head": ["Cobertura", "Límite orientativo", "Franquicia típica"],
                    "rows": [
                        ["Incendio y explosión (continente)", "Valor de reconstrucción", "Sin franquicia"],
                        ["Daños por agua", "Capital de continente y contenido", "100 a 150 EUR"],
                        ["Robo (contenido)", "Capital de contenido", "Sin franquicia o 150 EUR"],
                        ["Responsabilidad civil familiar", "300.000 a 600.000 EUR", "Sin franquicia"],
                        ["Objetos de valor", "Entre 3.000 y 6.000 EUR", "Sin franquicia"],
                        ["Daños eléctricos", "Hasta 1.500 EUR", "50 a 100 EUR"],
                    ],
                    "widths": [4, 3, 2],
                },
            },
            {
                "h": "6. Comparativa por tipo de cliente",
                "p": [
                    "La tabla siguiente ayuda a ordenar la conversación comercial según la situación del cliente. Es una guía, no una regla cerrada.",
                ],
                "table": {
                    "head": ["Tipo de cliente", "Prioridad", "Garantías clave", "Riesgo de error"],
                    "rows": [
                        ["Propietario con hipoteca", "Continente", "Incendio, agua, responsabilidad civil", "Infraseguro del valor de reconstrucción"],
                        ["Inquilino", "Contenido", "Robo, agua, responsabilidad civil", "Olvidar la responsabilidad civil frente al propietario"],
                        ["Segunda residencia", "Seguridad y desocupación", "Robo, fenómenos atmosféricos", "Exclusión por vivienda deshabitada"],
                        ["Propietario con objetos de valor", "Objetos de valor", "Capital específico y relación de bienes", "No declarar los objetos"],
                    ],
                    "widths": [3, 2, 4, 4],
                },
            },
            {
                "h": "7. Cancelaciones y renovaciones",
                "p": [
                    "La baja se solicita por escrito indicando la fecha y el motivo. Si la póliza se cancela dentro de los primeros 60 días desde la emisión, se produce una regularización de comisión frente a la aseguradora, por lo que debe consultarse siempre el motivo y valorar si el cliente aceptaría un cambio de producto o de aseguradora antes de tramitarla.",
                    "Un mes antes del vencimiento conviene revisar el valor del continente y del contenido, que suelen quedar desactualizados, así como las mejoras o reformas hechas en la vivienda. La renovación sin revisión es una fuente habitual de infraseguro.",
                ],
            },
            {
                "h": "8. Obligaciones, errores frecuentes y checklist",
                "p": [
                    "El corredor debe entregar la información precontractual, identificar la aseguradora, declarar su remuneración si se pregunta, advertir de cualquier conflicto de interés y tratar los datos personales con la finalidad declarada y las medidas de seguridad adecuadas.",
                    "Los errores más frecuentes son infraasegurar para ofrecer una prima baja, no preguntar por objetos de valor, asegurar como vivienda habitual una segunda residencia, y prometer verbalmente coberturas como el robo sin violencia que la póliza no incluye.",
                    "Lista de comprobación previa a la emisión:",
                ],
                "bullets": [
                    "Datos de la vivienda y régimen de tenencia confirmados.",
                    "Capitales de continente y contenido calculados con criterio.",
                    "Objetos de valor declarados o excluidos de forma expresa.",
                    "Exclusiones y franquicias explicadas al cliente.",
                    "Información precontractual entregada y consentimiento de datos recogido.",
                ],
            },
        ],
    },
    "D28": {
        "title": "Manual de seguros de Vida",
        "subtitle": "Guía de trabajo para el equipo comercial de SegurPlus",
        "version": "1.1",
        "date": "01/09/2026",
        "sections": [
            {
                "h": "1. Alcance y papel de SegurPlus",
                "p": [
                    "Este manual regula la comercialización del seguro de vida riesgo. SegurPlus es corredor y comercializa los productos de Vida Norte Seguros. No asume el riesgo, no valora el estado de salud del cliente ni gestiona los siniestros. Nuestra función es analizar la necesidad de protección, explicar el producto con claridad y acompañar al cliente. La comisión la abona el asegurador y debe informarse de ella cuando se pregunte.",
                    "Los capitales, edades y plazos que se citan son orientativos e ilustrativos. Las condiciones definitivas son las de la póliza de la aseguradora.",
                ],
            },
            {
                "h": "2. Coberturas y exclusiones",
                "p": [
                    "La garantía principal es el fallecimiento, que paga el capital pactado a los beneficiarios designados. Como complementarias existen la invalidez permanente absoluta, el fallecimiento por accidente con capital adicional y las enfermedades graves. Ninguna es obligatoria por ley, aunque una entidad financiera puede exigir un seguro de vida vinculado a un préstamo hipotecario.",
                ],
                "h2": [
                    {
                        "h": "2.1 Exclusiones habituales",
                        "p": ["Estas exclusiones deben explicarse siempre antes de la firma:"],
                        "bullets": [
                            "Suicidio durante el primer año de vigencia del contrato.",
                            "Declaración inexacta u omisión en el cuestionario de salud, salvo que no medie dolo y la ley lo permita.",
                            "Deportes de riesgo o actividades peligrosas no declaradas.",
                            "Participación en actos delictivos o guerra.",
                            "Enfermedades preexistentes no declaradas.",
                        ],
                    },
                ],
            },
            {
                "h": "3. Perfiles de cliente y qué preguntar",
                "p": [
                    "El seguro de vida se recomienda desde la necesidad económica de quienes dependen del asegurado. Un capital insuficiente deja a la familia sin protección; uno excesivo encarece la prima de forma innecesaria. El comercial debe indagar la situación personal sin presionar y dejar constancia de las respuestas, que son datos personales y, en el caso de salud, de categoría especial.",
                ],
                "bullets": [
                    "Edad, profesión y situación familiar, incluyendo hijos y personas dependientes.",
                    "Préstamos o hipotecas pendientes que la familia tendría que afrontar.",
                    "Ingresos del hogar y cuántos años de ingresos se quiere cubrir.",
                    "Seguros de vida ya contratados, incluido el del convenio colectivo.",
                    "Hábitos relevantes como el consumo de tabaco y deportes practicados.",
                    "Beneficiarios deseados y su relación con el asegurado.",
                ],
            },
            {
                "h": "4. Test de idoneidad y cuestionario de salud",
                "p": [
                    "En vida el corredor debe realizar el test de idoneidad antes de la contratación, para comprobar que el producto responde a las necesidades, el objetivo y la situación del cliente. El resultado se documenta y se conserva en el expediente. Si el producto no es idóneo, hay que advertírselo por escrito, aunque el cliente decida seguir adelante.",
                    "El cuestionario de salud lo responde el propio cliente bajo su responsabilidad. El comercial no debe sugerir respuestas ni minimizar antecedentes. Una omisión puede dar lugar a que la aseguradora rechace el pago del capital, con graves consecuencias para los beneficiarios.",
                ],
            },
            {
                "h": "5. Contratación, documentación y plazos",
                "p": [
                    "El proceso consiste en el análisis de necesidades, el test de idoneidad, la selección del capital y las garantías, la entrega de la información precontractual, el cuestionario de salud, la firma y la emisión. La documentación necesaria es el documento de identidad del asegurado, los datos de los beneficiarios, el cuestionario de salud firmado y, si la aseguradora lo exige, un reconocimiento médico.",
                    "El periodo de gracia para el pago del primer recibo es de un mes. El preaviso de no renovación es de un mes antes del vencimiento de la anualidad. Los contratos de vida contratados a distancia disponen de un derecho de desistimiento de 14 días naturales, que el cliente debe conocer y que debe constar en la documentación.",
                ],
            },
            {
                "h": "6. Tabla de coberturas y capitales orientativos",
                "p": [
                    "Los valores siguientes son ilustrativos. La aseguradora fija los suyos en la póliza, y pueden variar según edad, profesión y estado de salud.",
                ],
                "table": {
                    "head": ["Garantía", "Capital orientativo", "Observaciones"],
                    "rows": [
                        ["Fallecimiento", "50.000 a 300.000 EUR", "Garantía principal"],
                        ["Invalidez permanente absoluta", "Igual al capital de fallecimiento", "Opcional"],
                        ["Fallecimiento por accidente", "Capital adicional del 100 por ciento", "Opcional"],
                        ["Enfermedades graves", "20.000 a 100.000 EUR", "Opcional, con lista cerrada"],
                        ["Edad máxima de contratación", "65 a 70 años", "Según aseguradora"],
                        ["Carencia", "Sin carencia para accidente", "Variable en enfermedad"],
                    ],
                    "widths": [4, 4, 3],
                },
            },
            {
                "h": "7. Comparativa de modalidades",
                "p": [
                    "La siguiente tabla compara las modalidades más habituales de vida riesgo, de forma orientativa.",
                ],
                "table": {
                    "head": ["Modalidad", "Capital", "Prima", "Cuándo recomendarla"],
                    "rows": [
                        ["Temporal anual renovable", "Constante", "Crece con la edad", "Necesidad a corto plazo o de duración incierta"],
                        ["Temporal a prima nivelada", "Constante", "Estable durante el plazo", "Cobertura de una hipoteca de larga duración"],
                        ["Capital decreciente", "Disminuye con el préstamo", "Menor", "Garantía vinculada a un préstamo"],
                    ],
                    "widths": [3, 2, 3, 5],
                },
            },
            {
                "h": "8. Cancelaciones, obligaciones y checklist",
                "p": [
                    "Si el cliente cancela durante los primeros 60 días desde la emisión, se regulariza la comisión con la aseguradora. Distinga entre el desistimiento dentro de los 14 días, que no conlleva penalización para el cliente, y la baja posterior. La renovación debe revisarse un mes antes, porque la situación familiar y las deudas cambian.",
                    "El corredor debe entregar la información precontractual, realizar y documentar el test de idoneidad, declarar los posibles conflictos de interés y proteger los datos de salud con medidas reforzadas, limitando su acceso a quien deba tratarlos. Los errores frecuentes son infraasegurar, rellenar el cuestionario en nombre del cliente, omitir las exclusiones y no designar correctamente a los beneficiarios.",
                    "Lista de comprobación previa a la emisión:",
                ],
                "bullets": [
                    "Test de idoneidad completado y archivado.",
                    "Capital justificado con la situación familiar y las deudas.",
                    "Cuestionario de salud respondido personalmente por el cliente.",
                    "Beneficiarios identificados correctamente.",
                    "Derecho de desistimiento de 14 días comunicado.",
                    "Consentimiento de tratamiento de datos de salud recogido.",
                ],
            },
        ],
    },
    "D29": {
        "title": "Manual de seguros de Salud",
        "subtitle": "Guía de trabajo para el equipo comercial de SegurPlus",
        "version": "1.1",
        "date": "01/09/2026",
        "sections": [
            {
                "h": "1. Alcance y papel de SegurPlus",
                "p": [
                    "Este manual orienta la comercialización del seguro de salud. SegurPlus actúa como corredor entre el cliente y las aseguradoras Salud Mediterránea y Mutua Levante. No prestamos asistencia sanitaria, no autorizamos pruebas ni tratamientos y no gestionamos los siniestros, que corresponden a la aseguradora. Nuestro ingreso es la comisión que ésta paga, y debe comunicarse al cliente si lo solicita.",
                    "Los límites, copagos y plazos son orientativos e ilustrativos. Las condiciones definitivas son las que figuran en la póliza de la aseguradora.",
                ],
            },
            {
                "h": "2. Modalidades, coberturas y exclusiones",
                "p": [
                    "Las modalidades principales son el cuadro médico sin copago, el cuadro médico con copago y el reembolso de gastos. El cuadro médico limita la atención a los profesionales y centros concertados. El reembolso permite elegir libremente profesional y se paga un porcentaje de la factura, con un límite anual. Ninguna modalidad es obligatoria por ley.",
                ],
                "h2": [
                    {
                        "h": "2.1 Exclusiones y limitaciones habituales",
                        "p": ["Se explicarán siempre de forma expresa:"],
                        "bullets": [
                            "Enfermedades y lesiones preexistentes no declaradas.",
                            "Cirugía estética sin finalidad reparadora.",
                            "Tratamientos experimentales o no reconocidos.",
                            "Deportes de riesgo y accidentes laborales cubiertos por otro régimen.",
                            "Periodos de carencia en intervenciones, hospitalización y parto.",
                        ],
                    },
                ],
            },
            {
                "h": "3. Perfiles de cliente y qué preguntar",
                "p": [
                    "La modalidad adecuada depende de la edad, el estado de salud, la zona de residencia y el presupuesto. Una persona joven y sana puede preferir una prima baja con copago; una familia con hijos pequeños suele valorar el cuadro médico sin copago; quien viaja o quiere libre elección puede preferir el reembolso. Los datos de salud son categoría especial y solo deben recogerse cuando son necesarios para la contratación.",
                ],
                "bullets": [
                    "Edad de cada asegurado y composición familiar.",
                    "Ciudad de residencia y centros que utiliza habitualmente.",
                    "Si prefiere elegir profesional libremente o ajustarse a un cuadro médico.",
                    "Presupuesto mensual y tolerancia al copago.",
                    "Seguro actual y antigüedad en él, para tratar de mantener la continuidad.",
                    "Necesidades previstas: embarazo, tratamientos dentales o especialistas concretos.",
                ],
            },
            {
                "h": "4. Contratación, documentación y plazos",
                "p": [
                    "El proceso incluye la recogida de datos de cada asegurado, la comparación de ofertas, la entrega de la información precontractual, el cuestionario de salud, la firma y la emisión. Se necesita el documento de identidad del tomador y de los asegurados, los datos bancarios y el cuestionario de salud firmado por cada asegurado mayor de edad.",
                    "El periodo de gracia para el primer recibo es de un mes. El preaviso para no renovar es de un mes antes del vencimiento. Cuando el contrato se celebra a distancia, el cliente dispone de un derecho de desistimiento de 14 días naturales desde la contratación, que debe informarse y registrarse en la documentación.",
                ],
            },
            {
                "h": "5. Tabla orientativa de coberturas, copagos y carencias",
                "p": [
                    "Los valores siguientes son ilustrativos y no constituyen una oferta. Cada aseguradora define los suyos en la póliza.",
                ],
                "table": {
                    "head": ["Cobertura", "Límite o copago orientativo", "Carencia orientativa"],
                    "rows": [
                        ["Medicina general y pediatría", "Sin copago o 3 EUR por acto", "Sin carencia"],
                        ["Especialistas y pruebas diagnósticas", "Sin copago o 6 a 12 EUR", "Sin carencia o hasta 3 meses"],
                        ["Hospitalización y cirugía", "Sin copago", "6 meses"],
                        ["Parto y embarazo", "Sin copago", "8 meses"],
                        ["Reembolso de gastos", "80 por ciento hasta 100.000 EUR al año", "Variable"],
                        ["Urgencias", "Sin copago", "Sin carencia"],
                    ],
                    "widths": [4, 4, 3],
                },
            },
            {
                "h": "6. Comparativa de modalidades",
                "p": [
                    "La siguiente tabla resume las diferencias principales entre las modalidades, para facilitar la recomendación.",
                ],
                "table": {
                    "head": ["Modalidad", "Libertad de elección", "Prima", "Perfil recomendado"],
                    "rows": [
                        ["Cuadro médico con copago", "Solo cuadro concertado", "Baja", "Joven, sano y con presupuesto ajustado"],
                        ["Cuadro médico sin copago", "Solo cuadro concertado", "Media", "Familia que usa mucho el servicio"],
                        ["Reembolso de gastos", "Total", "Alta", "Quien quiere elegir libremente o viaja"],
                    ],
                    "widths": [3, 3, 2, 5],
                },
            },
            {
                "h": "7. Cancelaciones y renovaciones",
                "p": [
                    "Si el cliente cancela dentro de los primeros 60 días desde la emisión, se regulariza la comisión con la aseguradora. Distinga entre el desistimiento dentro de los 14 días y una baja posterior. Antes de tramitar la baja, pregunte el motivo, que a menudo es el precio o la insatisfacción con el cuadro médico, y valore alternativas.",
                    "Un mes antes del vencimiento debe revisarse la póliza. Las primas de salud suelen subir con la edad y la siniestralidad. Advierta al cliente de que cambiar de aseguradora implica nuevas carencias y la posible pérdida de la cobertura de enfermedades surgidas durante la póliza anterior.",
                ],
            },
            {
                "h": "8. Obligaciones, errores frecuentes y checklist",
                "p": [
                    "El corredor debe entregar la información precontractual, identificar la aseguradora y la modalidad, informar de su remuneración si se le pregunta, advertir de conflictos de interés y proteger los datos de salud limitando el acceso al personal que deba tratarlos. El cuestionario de salud lo contesta el propio cliente.",
                    "Los errores más frecuentes son prometer que un especialista o un centro concreto está en el cuadro médico sin comprobarlo, no explicar las carencias, rellenar el cuestionario de salud por el cliente y ocultar el copago. Otro error habitual es comparar solo la prima mensual sin revisar los límites anuales del reembolso, los servicios sujetos a autorización previa y la existencia de periodos de carencia distintos entre aseguradoras. Cuando el cliente tenga ya un seguro de salud, el comercial debe advertir de que las carencias pueden reiniciarse y recomendar que no cancele la póliza actual hasta confirmar la aceptación de la nueva.",
                    "Lista de comprobación previa a la emisión:",
                ],
                "bullets": [
                    "Cuadro médico consultado para los centros que interesan al cliente.",
                    "Carencias y copagos explicados y entendidos.",
                    "Cuestionario de salud respondido por cada asegurado.",
                    "Derecho de desistimiento de 14 días comunicado.",
                    "Información precontractual entregada y consentimiento de datos de salud recogido.",
                ],
            },
        ],
    },
}
