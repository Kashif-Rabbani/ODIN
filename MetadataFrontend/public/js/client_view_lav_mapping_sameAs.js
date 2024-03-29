/**
 * Created by snadal on 07/06/16.
 */

function getParameterByName(name) {
    name = name.replace(/[\[]/, "\\[").replace(/[\]]/, "\\]");
    var regex = new RegExp("[\\?&]" + name + "=([^&#]*)"),
        results = regex.exec(location.search);
    return results === null ? "" : decodeURIComponent(results[1].replace(/\+/g, " "));
}
function checkSelected(val) {
    var ret = false;
    $(".variable_priority option:selected").each(function() {
        if ($(this).val() === val) {
            ret = true;
        }
    });
    return ret;
}

function updateSameAs(lav_mapping){
    $.ajax({
        url: '/LAVMapping/sameAs',
        method: "POST",
        data: lav_mapping
    }).done(function() {
        window.location.href = '/manage_lav_mappings';
    }).fail(function(err) {
        alert("error "+JSON.stringify(err));
    });
}

var currWrapper;
var currLAVMapping;
var originalFeatures= [];

$(function() {
    $('#wariningModal').on('shown.bs.modal', function () {
        $('#myInput').trigger('focus')
    })
    originalFeatures = [];
    $.get("/LAVMapping/"+getParameterByName("LAVMappingID"), function(data) {
        var LAVMapping = (data);
        currLAVMapping =LAVMapping;
        $.get("/wrapper/"+LAVMapping.wrapperID, function(wrapper) {
            $("#wrapper").val(wrapper.name);
            currWrapper = wrapper;
        });

        $.get("/globalGraph/"+LAVMapping.globalGraphID, function(globalGraph) {
            $("#globalGraph").val(globalGraph.name);
            currGlobalGraph = globalGraph;
        }).done(function() {

            $.get("/globalGraph/"+encodeURIComponent(currGlobalGraph.namedGraph)+"/features", function(features) {
                currFeatures = features;
                var i = 0;
                _.each(data.sameAs,function(sameAs) {
                    $('#attributes').append($('<input class="form-control" type="text" id="attribute'+i+'" required="required" readonly="">').val(sameAs.attribute));
                    $('#features').append($('<select class="form-control variable_priority unique required" id="features'+i+'" type="text" name="features[]" required="required"></select>'));

                    $("#features"+i).empty().end();
                    var j = 1;
                    $('#features'+i).append($('<option>', { value:"feature0", text:"" } ));
                    _.each(currFeatures,function(feature) {
                        $('#features'+i).append($('<option>', { value:"feature"+j, text:feature } ));
                        if(feature === sameAs.feature){
                            originalFeatures[sameAs.attribute] = feature;
                            $('#features'+i).val("feature"+j);
                        }
                        ++j;
                    });
                    ++i;
                    $('#features'+i).val(sameAs.feature);

                });

                $('.variable_priority').change(function() {
                    $('option', this).each(function() {
                        if (checkSelected($(this).val()) && $(this).val() != "feature0") {
                            $('.variable_priority option[value=' + $(this).val() + ']').attr('disabled', true);
                        } else {
                            $('.variable_priority option[value=' + $(this).val() + ']').removeAttr('disabled');
                        }
                    });
                });

                $('.variable_priority').trigger("change");

            });
        });

        $('#submitLAVMapping').on("click", function(e){
            e.preventDefault();

            var lav_mapping = new Object();

            lav_mapping.wrapperID = currWrapper.wrapperID;
            lav_mapping.globalGraphID = currGlobalGraph.globalGraphID;
            lav_mapping.isModified = false;

            lav_mapping.LAVMappingID = currLAVMapping.LAVMappingID;

            lav_mapping.sameAs = new Array();
            for (var i = 0; i < $('#attributes input').length; ++i) {
                var from = $('#attribute' + i).val();
                var to = $('#features' + i+' option:selected').text();
                if (to != "" && originalFeatures[from] != to) {
                    var oneMapTo = new Object();
                    oneMapTo.featureOld = originalFeatures[from];
                    oneMapTo.featureNew = to;
                    lav_mapping.sameAs.push(oneMapTo);
                    lav_mapping.isModified = true;
                }
            }

            if(lav_mapping.isModified){
                if(currLAVMapping.graphicalSubGraph){
                    $("#btn_ok_modal").click(function (e) {
                        e.preventDefault();
                        updateSameAs(lav_mapping)
                    });
                    $('#warningModal').modal('show');
                }else{
                    updateSameAs(lav_mapping)
                }
            }else{
                //no changes done.
                window.location.href = '/manage_lav_mappings';
            }

        });


    });

});
